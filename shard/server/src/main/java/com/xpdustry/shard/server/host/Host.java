package com.xpdustry.shard.server.host;

import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.server.admin.*;
import com.xpdustry.shard.server.config.*;
import com.xpdustry.shard.server.config.ConfigKeys.*;
import com.xpdustry.shard.server.intent.*;
import com.xpdustry.shard.server.sched.*;
import com.xpdustry.shard.server.session.*;
import com.xpdustry.shard.server.sync.*;
import com.xpdustry.shard.server.world.*;
import com.xpdustry.shard.sim.ai.*;
import com.xpdustry.shard.sim.path.*;
import com.xpdustry.shard.sim.persist.ContentSource;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.team.Teams;
import com.xpdustry.shard.sim.world.*;
import org.slf4j.*;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/**
 * The server's hosted worlds and the routes between them (WP24, ARCHITECTURE.md §7): it loads maps into worlds
 * ({@link #host}), picks the world a new session joins ({@link #join}), moves sessions between worlds over the same
 * connection ({@link #transfer}) and stops worlds. Each world's tick-side work is its {@link HostedWorld}'s.
 */
public final class Host{
    private static final Logger LOG = LoggerFactory.getLogger(Host.class);

    private final Sessions sessions;
    private final WorldManager manager;
    private final ServerThreads threads;
    private final ConfigService config;
    private final MapCatalog maps;
    private final Epoch epoch;
    private final MsavReader reader;
    private final MapLoader loader;
    private final WorldSends sends;
    private final BuildingCodecs codecs = BuildingCodecs.builtIn();
    private final SyncSettings sync;
    private final InstantSource clock;
    /** Gameplay packets as intents (WP26). */
    private final Intents intents;
    /** Guarded by this; in hosting order. */
    private final LinkedHashMap<WorldId, HostedWorld> worlds = new LinkedHashMap<>();
    private final ConcurrentHashMap<Long, Session.Transfer> transfers = new ConcurrentHashMap<>();
    /** Sessions that confirmed a world at least once: vanilla's {@code hasConnected}. */
    private final Set<Long> connected = ConcurrentHashMap.newKeySet();
    private final AtomicInteger next = new AtomicInteger();
    private volatile boolean quiet;
    private volatile CaptureBudget captureBudget = CaptureBudget.defaults();
    private volatile Duration loadTimeout = DEFAULT_LOAD_TIMEOUT;
    private volatile int eventCapacity;
    private final CopyOnWriteArrayList<HostListener> listeners = new CopyOnWriteArrayList<>();
    private volatile Hooks hooks = Hooks.NONE;

    /**
     * How long a client may take to load a world and confirm it before it is kicked. Vanilla has no limit; this one
     * only frees the seat of a client that will never confirm, so it allows a large map over a slow link.
     */
    public static final Duration DEFAULT_LOAD_TIMEOUT = Duration.ofMinutes(2);
    /** How long {@link #stop} waits for the kicked sessions to leave before the world retires. */
    static final Duration STOP_DRAIN = Duration.ofSeconds(10);

    /**
     * Fault injection for the rig ({@code rig/scenarios/shard-loads.rig}): {@code -Dshard.debug.stream-delay=maze=3s}
     * deflates maze's world streams 3 s late, so a stock client can meet a stream that a transfer or {@code /sync}
     * fenced. Entries are {@code <world>=<n>ms|s}, comma-separated.
     */
    static final Map<String, Duration> STREAM_DELAYS = streamDelays(System.getProperty("shard.debug.stream-delay", ""));

    static Map<String, Duration> streamDelays(String property){
        var out = new HashMap<String, Duration>();
        for(String entry : property.split(",")){
            if(entry.isBlank()) continue;
            var matcher = java.util.regex.Pattern.compile("\\s*([a-z0-9-]+)=(\\d+)(ms|s)\\s*").matcher(entry);
            if(!matcher.matches()) throw new IllegalArgumentException("shard.debug.stream-delay: expected <world>=<n>ms|s, not '" + entry + "'");
            long n = Long.parseLong(matcher.group(2));
            out.put(matcher.group(1), matcher.group(3).equals("s") ? Duration.ofSeconds(n) : Duration.ofMillis(n));
        }
        return Map.copyOf(out);
    }

    public Host(Sessions sessions, WorldManager manager, ServerThreads threads, ConfigService config, MapCatalog maps, Epoch epoch,
                SyncSettings sync, InstantSource clock){
        this.sessions = sessions;
        this.manager = manager;
        this.threads = threads;
        this.config = config;
        this.maps = maps;
        this.epoch = epoch;
        this.sync = sync;
        this.clock = clock;
        reader = new MsavReader(epoch);
        loader = new MapLoader(epoch, BuildingCodecs.builtIn());
        sends = new WorldSends(epoch, BuildingCodecs.builtIn());
        intents = new Intents(sessions, config);
    }

    /** No per-player log lines (the load test). */
    public void quiet(boolean quiet){
        this.quiet = quiet;
    }

    /** The memory world streams may hold between capture and send. */
    public CaptureBudget captureBudget(){
        return captureBudget;
    }

    /** Replaces the budget; only before the first join, or captures in flight release into the old one. */
    public void captureBudget(CaptureBudget budget){
        this.captureBudget = Objects.requireNonNull(budget, "budget");
    }

    public Duration loadTimeout(){
        return loadTimeout;
    }

    public void loadTimeout(Duration timeout){
        if(timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("load timeout must be positive: " + timeout);
        this.loadTimeout = timeout;
    }

    /** The host's event queue size for worlds attached from now on; 0 for three times the inbox. Tests force overflows. */
    int eventCapacity(){
        return eventCapacity;
    }

    void eventCapacity(int capacity){
        this.eventCapacity = capacity;
    }

    public Sessions sessions(){
        return sessions;
    }

    public MapCatalog maps(){
        return maps;
    }

    /** Decodes gameplay packets into intents and delivers what their admission sends (WP26). */
    public Intents intents(){
        return intents;
    }

    public WorldManager manager(){
        return manager;
    }

    /** Adds a listener every world runs after each of its ticks, from its next tick on (chat, the game flow, plugins). */
    public void listen(HostListener listener){
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    List<HostListener> listeners(){
        return listeners;
    }

    ServerThreads threads(){
        return threads;
    }

    WorldSends sends(){
        return sends;
    }

    /** The sync cadence and bounds worlds start from; each world's snapshot interval is its own. */
    SyncSettings sync(){
        return sync;
    }

    Epoch epoch(){
        return epoch;
    }

    BuildingCodecs codecs(){
        return codecs;
    }

    /** The config as world {@code id} sees it. */
    public Config config(WorldId id){
        return config.current().world(id.name());
    }

    void log(String message){
        if(!quiet) LOG.info(message);
    }

    // ---- worlds

    /**
     * A parsed {@code host.worlds} entry: {@code [<id>=]<map> [<mode>]}; or, with {@code save}, a save file the console's
     * {@code load} hosts as vanilla's {@code SaveIO.load} does ({@link #hostSave}).
     */
    public record Spec(WorldId id, MapCatalog.Entry map, Gamemode mode, boolean save){
        public Spec(WorldId id, MapCatalog.Entry map, Gamemode mode){
            this(id, map, mode, false);
        }
    }

    /**
     * Reads a {@code host.worlds} entry, or the console's {@code host <map> [mode] [id]}.
     *
     * @throws IllegalArgumentException naming what is wrong
     */
    public Spec spec(String entry){
        String text = entry.trim();
        String id = null;
        int equals = text.indexOf('=');
        if(equals >= 0){
            id = text.substring(0, equals).trim();
            text = text.substring(equals + 1).trim();
        }
        String[] words = text.split("\\s+");
        if(text.isEmpty() || words.length > 2) throw new IllegalArgumentException("expected [<id>=]<map> [<mode>], not '" + entry + "'");
        MapCatalog.Entry map = maps.find(words[0]).orElseThrow(() -> new IllegalArgumentException("no map named '" + words[0] + "'"));
        Gamemode mode = words.length > 1 ? mode(words[1]) : Gamemode.SURVIVAL;
        String name = id == null ? MapCatalog.defaultId(map) : id;
        if(!ConfigKeys.WORLD_ID.matcher(name).matches()){
            throw new IllegalArgumentException("bad world id '" + name + "': lower case letters, digits and dashes");
        }
        return new Spec(WorldId.of(name), map, mode);
    }

    /** Vanilla's {@code Gamemode.valueOf} on the console's lower-case names. */
    static Gamemode mode(String name){
        for(Gamemode mode : Gamemode.values()){
            if(mode.name().equalsIgnoreCase(name)) return mode;
        }
        throw new IllegalArgumentException("no gamemode '" + name + "'");
    }

    /**
     * Loads {@code spec}'s map into a new world and runs it: the file is read and parsed on {@code io-cpu}, the
     * map's generation filters run, the world is {@linkplain #load made ready to run}, then the first checkpoint is
     * published. A world id hosted before starts over: its old generations are archived.
     */
    public CompletableFuture<HostedWorld> host(Spec spec){
        HostedWorld hosted;
        try{
            hosted = world(spec);
        }catch(IllegalArgumentException e){
            return CompletableFuture.failedFuture(e);
        }
        synchronized(this){
            if(worlds.containsKey(spec.id()) || manager.get(spec.id()).isPresent()){
                return CompletableFuture.failedFuture(new IllegalStateException("world " + spec.id() + " is already hosted; stop it first"));
            }
            worlds.put(spec.id(), hosted);
        }
        return start(spec, hosted);
    }

    /**
     * Hosts {@code file} as a new world {@code id}, as the console's {@code load} does (ServerControl.java:1183): vanilla's
     * {@code SaveIO.load}, then {@code state.set(playing)}. Unlike {@link #host}, the file's own rules play
     * ({@link MapLoader#saveRules}), its generation filters don't run, and {@code logic.play()} doesn't: no loadout, no
     * heal, the wave countdown and the weather as saved. Its mode is the one that fits its rules
     * ({@code state.rules.mode()}).
     */
    public CompletableFuture<HostedWorld> hostSave(WorldId id, MapCatalog.Entry file){
        Gamemode mode;
        try{
            mode = loader.saveRules(reader.read(file.read())).mode();
        }catch(IOException | RuntimeException e){
            return CompletableFuture.failedFuture(e);
        }
        return host(new Spec(id, file, mode, true));
    }

    /** A world for {@code spec}, not hosted yet: its mailbox takes seats and joins, which wait for its first tick. */
    private HostedWorld world(Spec spec){
        WorldId id = spec.id();
        Config world = config(id);
        int capacity = world.get(ConfigKeys.INTENTS_INBOX_CAPACITY), reserve = world.get(ConfigKeys.INTENTS_INBOX_RESERVE);
        if(reserve >= capacity){
            throw new IllegalArgumentException("intents.inbox-reserve (" + reserve + ") must stay below intents.inbox-capacity (" + capacity + ")");
        }
        var mailbox = new WorldMailbox(capacity, world.get(ConfigKeys.INTENTS_MAILBOX_BOUND), world.get(ConfigKeys.INTENTS_MAILBOX_QUANTUM),
            reserve, world.get(ConfigKeys.HOST_WORLD_PLAYER_LIMIT));
        var hosted = new HostedWorld(this, id, spec.map(), spec.mode(), mailbox, snapshotTicks(world.get(ConfigKeys.NET_SNAPSHOT_INTERVAL)));
        Duration delay = STREAM_DELAYS.get(id.name());
        if(delay != null){
            LOG.info("{}: world streams are deflated {} ms late (shard.debug.stream-delay)", id, delay.toMillis());
            hosted.encodeOn(CompletableFuture.delayedExecutor(delay.toMillis(), TimeUnit.MILLISECONDS, threads.ioCpu()));
        }
        return hosted;
    }

    /** Loads {@code spec}'s map into {@code hosted}, which {@link #worlds} lists already, and runs it. */
    private CompletableFuture<HostedWorld> start(Spec spec, HostedWorld hosted){
        WorldId id = spec.id();
        int capacity = config(id).get(ConfigKeys.INTENTS_INBOX_CAPACITY);
        long seed = id.hash();
        return CompletableFuture.supplyAsync(() -> prepare(spec, capacity, seed), threads.ioCpu())
            .thenCompose(prepared -> manager.create(id, prepared.config(), () -> {
                MapLoader.Loaded loaded = spec.save() ? loadSave(loader, prepared.save(), prepared.config(), prepared.rules())
                    : load(loader, prepared.save(), prepared.config(), prepared.rules());
                WorldSends.Warm warm = sends.warm(loaded.world(), loaded.state());
                hosted.loaded(loaded.state(), warm.footprint());
                log(id + ": a world stream is " + warm.deflated() + " B (" + warm.raw() + " B raw); a capture holds "
                    + warm.footprint().capture() + " B, a stream's encode " + warm.footprint().stream() + " B");
                return loaded.world();
            }, hosted))
            .handle((managed, failure) -> {
                if(failure != null){
                    synchronized(this){
                        worlds.remove(id, hosted);
                    }
                    throw new CompletionException(failure instanceof CompletionException ? failure.getCause() : failure);
                }
                hosted.managed(managed);
                // joins that waited in its mailbox while the map loaded go in at its first tick: it opens active
                LOG.info("hosting {}", hosted.describe());
                return hosted;
            });
    }

    /**
     * Replaces world {@code old}'s map with {@code map}, keeping the world's id, as vanilla's {@code WorldReloader}
     * reloads every player onto the next map: the new world takes {@code old}'s place in the list at once, every
     * session playing in {@code old} moves to it over its connection ({@code worldDataBegin}, then the new map once it
     * loaded), and {@code old} retires once it has removed them all. A session still loading {@code old}, or on its
     * way there, is kicked to join again, since it can't move before it plays; so is one whose route there starts
     * after this scan ({@link #join}, {@link #transfer}), and once {@code old} retired its mailbox gives no seat.
     *
     * @return the new world, running; if its map fails to load, the sessions waiting for it are kicked
     */
    public CompletableFuture<HostedWorld> replace(HostedWorld old, MapCatalog.Entry map, Gamemode mode){
        var spec = new Spec(old.id(), map, mode);
        HostedWorld next;
        try{
            next = world(spec);
        }catch(IllegalArgumentException e){
            return CompletableFuture.failedFuture(e);
        }
        // WorldReloader.begin: the players in Groups.player order with their teams, as of old's last tick
        next.reloaded(old.seats());
        synchronized(this){
            if(worlds.get(old.id()) != old) return CompletableFuture.failedFuture(new IllegalStateException("world " + old.id() + " is no longer hosted"));
            worlds.put(old.id(), next);
        }
        for(Session session : sessions.all()){
            boolean in = session.world() == old;
            if(!in && pendingTo(session) != old) continue;
            if(!in || !move(session, next)) mapChanged(session);
        }
        LOG.info("{}: the next map is {}; moving its players", old.id(), map.name());
        return drained(old)
            .thenCompose(ignored -> manager.retire(old.id()))
            .whenComplete((ignored, failure) -> retired(old, failure))
            .thenCompose(ignored -> start(spec, next))
            .whenComplete((hosted, failure) -> {
                if(failure == null) return;
                for(Session session : sessions.all()){
                    if(session.world() == next || pendingTo(session) == next){
                        session.conn().closeAfter(Calls.kick("[scarlet]The next map failed to load."), "next map failed");
                    }
                }
                LOG.error("{}: the next map {} failed to load", old.id(), map.name(), failure);
            });
    }

    /**
     * Completes once {@code world} has no members left (or isn't running), checked after its ticks. Each check is
     * queued from off the tick thread, a period later: queued from the inspection itself, it would run in the same
     * pass of the world's tasks, again and again, and the world would never tick to take the leaves.
     */
    private CompletableFuture<Void> drained(HostedWorld world){
        return world.inspect(w -> w.members().size()).exceptionally(failure -> 0).thenComposeAsync(members -> {
            if(members == 0) return CompletableFuture.completedFuture(null);
            return drained(world);
        }, CompletableFuture.delayedExecutor(WorldScheduler.PERIOD, TimeUnit.NANOSECONDS, threads.io()));
    }

    private record Prepared(SaveFile save, Rules rules, WorldConfig config){
    }

    /**
     * Loads a map into a world that is ready to run: vanilla's {@code logic.play()} starts the game ({@link
     * MapLoader#play}: the loadout and heal on the cores, the wave countdown, the weather, storage linked to cores,
     * saved units alive; game over is only checked after it), then the rules model pushes its {@link PathRules}, and
     * the path service settles them and the load's full refresh here, off the tick threads. In the first tick, that
     * refresh took 20-40 ms on a dev box, and on a busy 4-vCPU VM it put debris-field more than 3 ticks behind. The
     * wave team's preloaded fields are taken and built here too ({@link WaveFields#preloadNow}): 30-45 ms of
     * archipelago's first tick.
     */
    static MapLoader.Loaded load(MapLoader loader, SaveFile save, WorldConfig config, Rules rules){
        MapLoader.Loaded loaded = loader.loadMap(save, config, rules);
        MapLoader.play(loaded);
        return ready(loaded);
    }

    /**
     * Loads a save into a world that is ready to run, as the console's {@code load}: {@code SaveIO.load} and
     * {@code state.set(State.playing)}, which makes it a game ({@link Teams#play}) without {@code logic.play()}; then
     * the paths as {@link #load}.
     */
    static MapLoader.Loaded loadSave(MapLoader loader, SaveFile save, WorldConfig config, Rules rules){
        MapLoader.Loaded loaded = loader.load(save, config, rules);
        Teams teams = Teams.of(loaded.world());
        if(teams != null) teams.play();
        return ready(loaded);
    }

    /** The rules model's {@link PathRules}, the path service settled, the wave team's fields preloaded. */
    private static MapLoader.Loaded ready(MapLoader.Loaded loaded){
        World world = loaded.world();
        world.part(WorldRules.class).pushPathRules(world, loaded.state().wave);
        PathService paths = world.part(PathService.class);
        paths.settle(world);
        WaveFields.preloadNow(world);
        paths.settle(world);
        return loaded;
    }

    /** On io-cpu: the file parsed, the rules the mode gives it, the world's config at the map's size. */
    private Prepared prepare(Spec spec, int capacity, long seed){
        try{
            byte[] bytes = spec.map().read();
            SaveFile save = reader.read(bytes);
            Rules rules = spec.save() ? loader.saveRules(save) : loader.hostRules(save, spec.mode());
            WorldConfig template = HostedSystems.template(epoch, seed, capacity);
            // the map's own content, its data patches applied (MapLoader.contentOf), rebuilt from the file on every
            // start: the world's checkpoints name its hash, so a wake, a recovery or a resume after a restart restores
            // with it; remembered for the in-process readers (replay, crash bundles)
            Epoch content = loader.contentOf(save);
            if(content.isPatched()) ContentSource.remember(content);
            var config = new WorldConfig(content,save.map().width(), save.map().height(), seed, template.systems(), template.kinds(),
                template.inboxCapacity(), template.journal());
            // with persistence.resume, the same map and mode go on from the world's last checkpoint
            String map = (spec.save() ? "save:" : "") + spec.map().file();
            Path archived = manager.prepare(spec.id(), new TreeMap<>(Map.of("map", map, "mode", spec.mode().name())), clock.millis());
            if(archived != null) LOG.info("{}: earlier checkpoints moved to {}", spec.id(), archived);
            return new Prepared(save, rules, config);
        }catch(IOException e){
            throw new CompletionException(e);
        }
    }

    /** Ticks between snapshots: the interval rounded to the 60 Hz grid, at least one. */
    static int snapshotTicks(Duration interval){
        return (int)Math.max(1, Math.round(interval.toNanos() / (double)WorldScheduler.PERIOD));
    }

    /**
     * Stops a world: its sessions are kicked with {@code reason} (vanilla's {@code stop} closes the server with
     * {@code serverClose}), its final checkpoint is published and it leaves the host.
     */
    public CompletableFuture<Void> stop(WorldId id, KickReason reason){
        HostedWorld hosted;
        synchronized(this){
            hosted = worlds.remove(id);
        }
        if(hosted == null) return CompletableFuture.failedFuture(new IllegalArgumentException("no world " + id));
        for(Session session : sessions.all()){
            if(session.world() == hosted || pendingTo(session) == hosted){
                session.conn().closeAfter(Calls.kick(reason), "world " + id + " stopped");
            }
        }
        // the kicked sessions' leaves reach the world before it retires, or they are never forgotten; a client that
        // takes longer than STOP_DRAIN to close is left to the retirement
        return drained(hosted).completeOnTimeout(null, STOP_DRAIN.toMillis(), TimeUnit.MILLISECONDS)
            .thenCompose(ignored -> manager.retire(id))
            .whenComplete((ignored, failure) -> {
                retired(hosted, failure);
                LOG.info("stopped {}", id);
            });
    }

    /**
     * {@code world} no longer ticks: its mailbox is released, so the seats of routes that started there late (see
     * {@link #join}) come back and none starts there again, and every listener hears it retired.
     */
    private void retired(HostedWorld world, Throwable failure){
        if(failure == null) world.mailbox().release();
        for(HostListener listener : listeners){
            try{
                listener.retired(world);
            }catch(RuntimeException e){
                LOG.error("{}: a host listener failed on the world's retirement", world.id(), e);
            }
        }
    }

    private Destination pendingTo(Session session){
        Session.Transfer transfer = transfers.get(session.id());
        return transfer == null ? null : transfer.to();
    }

    /** Whether {@code world} is still hosted. */
    synchronized boolean hosts(HostedWorld world){
        return worlds.get(world.id()) == world;
    }

    public synchronized List<HostedWorld> worlds(){
        return List.copyOf(worlds.values());
    }

    public synchronized Optional<HostedWorld> world(String id){
        for(HostedWorld world : worlds.values()){
            if(world.id().name().equals(id)) return Optional.of(world);
        }
        return Optional.empty();
    }

    /** Sessions routed to a world or on their way, about now: what {@code net.player-limit} counts. */
    public int players(){
        int count = 0;
        for(Session session : sessions.all()){
            if(session.state() != SessionState.CLOSED && session.state() != SessionState.CONNECTED) count++;
        }
        return count;
    }

    // ---- routes

    /** The worlds a new session may join, best first, by {@code host.join}. */
    List<HostedWorld> candidates(){
        List<HostedWorld> open = worlds().stream().filter(HostedWorld::available).toList();
        if(open.isEmpty()) return open;
        return switch(config.current().get(ConfigKeys.HOST_JOIN)){
            case FIRST -> open;
            case FEWEST -> fewestFirst(open, world -> world.mailbox().seats());
            case ROUND_ROBIN -> {
                int start = Math.floorMod(next.getAndIncrement(), open.size());
                var out = new ArrayList<HostedWorld>(open.size());
                for(int i = 0; i < open.size(); i++) out.add(open.get((start + i) % open.size()));
                yield out;
            }
        };
    }

    /**
     * {@code worlds} by {@code seats}, fewest first, ties in their order. Other joins take seats during the sort, so
     * it sorts one reading of each: a comparator that reads them live can contradict itself, and TimSort then throws
     * "Comparison method violates its general contract!" (W2.4's load gate lost a join to it about once a run).
     */
    static <T> List<T> fewestFirst(List<T> worlds, ToIntFunction<T> seats){
        var read = new ArrayList<Map.Entry<T, Integer>>(worlds.size());
        for(T world : worlds) read.add(Map.entry(world, seats.applyAsInt(world)));
        read.sort(Map.Entry.comparingByValue());
        return read.stream().map(Map.Entry::getKey).toList();
    }

    /**
     * Routes a connected session to a world with a seat for it.
     *
     * @return the world, or null if none has room (or the session closed meanwhile, or the world it joined left the
     *     host meanwhile: the session is then kicked)
     */
    public HostedWorld join(Session session){
        // twice at most: a world that left the host since its candidates were picked refuses the join, and the
        // world that replaced it may take it
        for(int pass = 0; pass < 2; pass++){
            List<HostedWorld> candidates = candidates();
            hooks.picked(session, candidates);
            boolean gone = false;
            for(HostedWorld world : candidates){
                if(session.join(world)){
                    if(hosts(world)) return world;
                    // a replace or a stop took the world off the host after it was picked, and its scan of the
                    // sessions ran before this one had a world: it goes the way the scan sends those still loading
                    left(session, world);
                    return null;
                }
                if(session.state() != SessionState.CONNECTED) return null;
                gone |= !hosts(world);
            }
            if(!gone) return null;
        }
        return null;
    }

    /**
     * Starts moving {@code session} to {@code to}: the source removes the player at its next tick boundary, then
     * the session loads {@code to} over the same connection.
     *
     * @return false if it can't move now: not in a world, already there, or {@code to} has no seat
     */
    public boolean transfer(Session session, HostedWorld to){
        if(!to.available()) return false;
        hooks.moving(session, to);
        if(!move(session, to)) return false;
        if(hosts(to)) return true;
        // as a join: the world left the host after the check, and its scan missed the session on its way there
        left(session, to);
        return false;
    }

    /** Kicks {@code session}, whose route to {@code world} started after a replace or a stop took it off the host. */
    private void left(Session session, HostedWorld world){
        boolean replaced;
        synchronized(this){
            replaced = worlds.containsKey(world.id());
        }
        if(replaced) mapChanged(session);
        else session.conn().closeAfter(Calls.kick(KickReason.SERVER_CLOSE), "world " + world.id() + " stopped");
    }

    /** {@link #replace}'s kick of a session that can't move: still loading the old map, or on its way there. */
    private static void mapChanged(Session session){
        session.conn().closeAfter(Calls.kick("[scarlet]The map changed while you were joining.[]\nPlease join again."), "map changed while joining");
    }

    /** Fixed points of a route, where tests hold one; each runs on the routing thread. */
    interface Hooks{
        Hooks NONE = new Hooks(){
        };

        /** {@link #join} picked {@code candidates} for {@code session}, and joins one of them next. */
        default void picked(Session session, List<HostedWorld> candidates){
        }

        /** {@link #transfer} found {@code to} available, and moves {@code session} there next. */
        default void moving(Session session, HostedWorld to){
        }
    }

    void hooks(Hooks hooks){
        this.hooks = Objects.requireNonNull(hooks, "hooks");
    }

    /** {@link #transfer}, to a world that may not run yet: a rotation's next map, whose mailbox holds the join. */
    private boolean move(Session session, HostedWorld to){
        // recorded under the session's lock, which departed() takes; the source is woken after, outside it
        return session.beginTransfer(to, transfer -> transfers.put(session.id(), transfer)) != null;
    }

    /**
     * From {@code from}'s tick thread, once it removed the session's player: if the session is moving out, it now
     * loads the destination ({@code worldDataBegin} once older streams are fenced, then the destination's join).
     *
     * @return whether the leave was a transfer's
     */
    boolean departed(Session session, HostedWorld from){
        Session.Transfer transfer;
        synchronized(session){
            transfer = transfers.get(session.id());
            if(transfer == null || transfer.from() != from) return false;
            transfers.remove(session.id(), transfer);
        }
        if(session.arrive(transfer, Calls.WORLD_DATA_BEGIN)){
            log(session + " moves from " + from.id() + " to " + transfer.to().name() + " (route " + transfer.generation() + ")");
        }else if(session.state() == SessionState.CONNECTED){
            // the destination failed meanwhile: anywhere else will do
            HostedWorld other = join(session);
            // a join kicked as late is on its way out already
            if(other == null && session.state() == SessionState.CONNECTED) session.conn().closeAfter(Calls.kick("[scarlet]The world you were moving to stopped, and no other has room."), "transfer failed");
        }
        return true;
    }

    /** True once per session: its first {@code connectConfirm} anywhere. */
    boolean firstConfirm(Session session){
        return connected.add(session.id());
    }

    /** The connection closed: forget what the host kept for the session. */
    public void closed(Session session){
        transfers.remove(session.id());
        connected.remove(session.id());
    }
}
