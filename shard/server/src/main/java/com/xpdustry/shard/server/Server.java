package com.xpdustry.shard.server;

import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.net.*;
import com.xpdustry.shard.server.admin.*;
import com.xpdustry.shard.server.chat.*;
import com.xpdustry.shard.server.config.*;
import com.xpdustry.shard.server.connect.*;
import com.xpdustry.shard.server.game.*;
import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.server.ops.*;
import com.xpdustry.shard.server.replay.*;
import com.xpdustry.shard.server.sched.*;
import com.xpdustry.shard.server.session.*;
import com.xpdustry.shard.server.store.Store;
import com.xpdustry.shard.server.sync.*;
import com.xpdustry.shard.server.ui.*;
import com.xpdustry.shard.server.world.*;
import org.slf4j.*;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * A running Shard server (ARCHITECTURE.md §7): the config, the store and the admin model, the ops API, the world
 * scheduler and manager, the hosted worlds, the sessions and the transport. {@link #start} brings them up in that
 * order and hosts {@code host.worlds}; {@link #close} takes them down the other way, kicking every client with
 * {@code serverClose} and publishing each world's final checkpoint.
 */
public final class Server implements AutoCloseable{
    private static final Logger LOG = LoggerFactory.getLogger(Server.class);

    private final ConfigService config;
    private final Store store;
    private final AdminService admin;
    private final OpsServer ops;
    private final Metrics metrics;
    private final ServerThreads threads;
    private final WorldScheduler scheduler;
    private final WorldManager manager;
    private final Sessions sessions;
    private final Host host;
    private final ConnectFlow flow;
    private final Chat chat;
    private final GameFlow game;
    private final Ui ui;
    private final Transport transport;
    private final ScheduledExecutorService timer;
    /** Match recording and published view streams ({@code replay.*}). */
    private volatile Replays replays;
    private volatile boolean closed;

    private Server(ConfigService config, Store store, AdminService admin, OpsServer ops, Metrics metrics, ServerThreads threads,
                   WorldScheduler scheduler, WorldManager manager, Sessions sessions, Host host, ConnectFlow flow, Chat chat, GameFlow game,
                   Ui ui, Transport transport, ScheduledExecutorService timer){
        this.config = config;
        this.store = store;
        this.admin = admin;
        this.ops = ops;
        this.metrics = metrics;
        this.threads = threads;
        this.scheduler = scheduler;
        this.manager = manager;
        this.sessions = sessions;
        this.host = host;
        this.flow = flow;
        this.chat = chat;
        this.game = game;
        this.ui = ui;
        this.transport = transport;
        this.timer = timer;
    }

    /**
     * How {@code shard} was started.
     *
     * @param netClock the transport's clock, in nanoseconds ({@link Transport#bind(InetSocketAddress, Transport.Config,
     *     Transport.Handler, LongSupplier)}): {@link System#nanoTime} outside tests, which move their own past a timeout
     */
    public record Options(Path configDir, Map<String, String> env, boolean quiet, LongSupplier netClock){
        public Options{
            Objects.requireNonNull(netClock, "netClock");
        }

        public Options(Path configDir, Map<String, String> env, boolean quiet){
            this(configDir, env, quiet, System::nanoTime);
        }
    }

    /**
     * Starts a server. The data directory (the database's) becomes {@code org.sqlite.tmpdir}, so SQLite's native
     * library isn't unpacked into {@code /tmp}.
     *
     * @throws ConfigException if the config doesn't validate
     */
    public static Server start(Options options) throws IOException, ConfigException{
        var config = new ConfigService(() -> ConfigLoader.loadDirectory(options.configDir(), options.env()));
        Config current = config.current();
        Logging.configure(current);
        Logging.follow(config);

        Path database = Path.of(current.get(ConfigKeys.STORAGE_DATABASE)).toAbsolutePath();
        Path data = database.getParent();
        Files.createDirectories(data);
        System.setProperty("org.sqlite.tmpdir", data.toString());

        InstantSource clock = InstantSource.system();
        Store store = Store.open(Store.Settings.from(current), clock);
        AdminService admin = AdminService.start(store, config::current, clock);

        var metrics = new Metrics();
        var health = new Health();
        Instrumentation.jvm(metrics, health);
        Instrumentation.config(metrics, config);
        Instrumentation.store(metrics, health, store);
        OpsServer ops = null;
        if(current.get(ConfigKeys.OPS_ENABLED)){
            ops = OpsServer.builder().health(health).metrics(metrics).config(config).events(new Events())
                .start(current.get(ConfigKeys.OPS_BIND), current.get(ConfigKeys.OPS_PORT));
            LOG.info("ops API on http://{}", ops.address());
        }

        var threads = new ServerThreads(ServerThreads.Sizes.forThisMachine());
        var scheduler = new WorldScheduler(threads.scheduler(),
            stall -> LOG.warn("world {} stalled for {} ms in its {}", stall.world(), stall.elapsedNanos() / 1_000_000, stall.step()));
        var checkpoints = new CheckpointStore(Path.of(current.get(ConfigKeys.PERSISTENCE_DIRECTORY)).toAbsolutePath(), FileOps.SYSTEM,
            current.get(ConfigKeys.PERSISTENCE_KEEP_RECENT));
        var manager = new WorldManager(threads, scheduler, checkpoints, policy(current));
        // per world and live: persistence.match-checkpoints and replay.record reload without a restart
        manager.matchEvery(world -> matchTicks(config.current().world(world.name())));

        Epoch epoch = Epoch.vanilla();
        String jar = current.get(ConfigKeys.MAPS_VANILLA_JAR);
        MapCatalog maps = MapCatalog.load(jar.isEmpty() ? null : Path.of(jar), Path.of(current.get(ConfigKeys.MAPS_DIRECTORY)), new MsavReader(epoch));
        LOG.info("{} maps: {}", maps.all().size(), String.join(", ", maps.all().stream().map(MapCatalog.Entry::file).toList()));

        var sessions = new Sessions();
        var sync = SyncSettings.DEFAULT.withUniverseSeconds(current.get(ConfigKeys.GAME_UNIVERSE_SECONDS));
        var host = new Host(sessions, manager, threads, config, maps, epoch, sync, clock);
        host.quiet(options.quiet());
        // before the first world is hosted: it records from its first tick
        Replays replays = Replays.start(config, host, threads);
        var chat = new Chat(host, admin, config);
        chat.quiet(options.quiet());
        // the rotation's seed, drawn per start as vanilla's Mathf.rand: logged, so a run's maps can be drawn again
        long seed = new Random().nextLong();
        LOG.info("rotation seed {}", seed);
        var game = new GameFlow(host, new Rotation(host, seed));
        var ui = new Ui(host);
        var flow = new ConnectFlow(host, admin, config, chat);
        flow.quiet(options.quiet());

        // the startup worlds first, as vanilla hosts its map before it listens: nobody joins before ready
        Transport transport;
        try{
            hostAll(host, current.get(ConfigKeys.HOST_WORLDS), threads.sizes().ioCpu());
            InetAddress bind = current.get(ConfigKeys.NET_BIND);
            var transportConfig = Transport.Config.defaults().withAllowPublic(!bind.isLoopbackAddress()).withLimits(ConnectFlow.limits(current));
            transport = Transport.bind(new InetSocketAddress(bind, current.get(ConfigKeys.NET_PORT)), transportConfig, flow, options.netClock());
        }catch(IOException | RuntimeException e){
            abandon(manager, scheduler, threads, ops, store);
            throw e;
        }
        LOG.info("listening on {} (tcp+udp), java {}, {} tick threads", transport.address(), Runtime.version(), threads.sizes().tick());

        var timer = Executors.newSingleThreadScheduledExecutor(task -> Thread.ofPlatform().name("server-timer").daemon().unstarted(task));
        var server = new Server(config, store, admin, ops, metrics, threads, scheduler, manager, sessions, host, flow, chat, game, ui, transport, timer);
        server.replays = replays;
        server.instrument();
        timer.scheduleAtFixedRate(server::refreshDiscovery, 0, 5, TimeUnit.SECONDS);
        if(host.worlds().isEmpty()) LOG.warn("no world is hosted: set host.worlds, or type 'host <map>'");
        LOG.info("ready: {} worlds hosted, {} MB of heap in use", host.worlds().size(),
            (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) >> 20);
        return server;
    }

    /** Hosts {@code host.worlds}, as many maps at once as io-cpu has threads: its queue is bounded. */
    private static void hostAll(Host host, List<String> entries, int batch) throws IOException{
        for(int from = 0; from < entries.size(); from += batch){
            var hosting = new LinkedHashMap<String, CompletableFuture<HostedWorld>>();
            for(String entry : entries.subList(from, Math.min(entries.size(), from + batch))){
                try{
                    hosting.put(entry, host.host(host.spec(entry)));
                }catch(IllegalArgumentException e){
                    hosting.put(entry, CompletableFuture.failedFuture(e));
                }
            }
            for(var entry : hosting.entrySet()){
                try{
                    entry.getValue().join();
                }catch(CompletionException e){
                    throw new IOException("host.worlds: can't host '" + entry.getKey() + "': " + cause(e), e);
                }
            }
        }
    }

    /** Closes what {@link #start} opened before it failed, with nothing listening yet. */
    private static void abandon(WorldManager manager, WorldScheduler scheduler, ServerThreads threads, OpsServer ops, Store store){
        try{
            manager.close();
        }catch(RuntimeException e){
            LOG.error("worlds didn't all hibernate", e);
        }
        scheduler.close();
        threads.close();
        if(ops != null) ops.close();
        store.close();
    }

    /** The lifecycle policy of {@code persistence.*} and {@code game.simulate-when-empty}. */
    static LifecyclePolicy policy(Config config){
        Duration hibernate = config.get(ConfigKeys.PERSISTENCE_HIBERNATE_AFTER);
        LifecyclePolicy base = LifecyclePolicy.DEFAULT;
        // 0 never hibernates
        Duration after = hibernate.isZero() ? Duration.ofDays(36_500) : hibernate.compareTo(base.idleAfter()) < 0 ? base.idleAfter() : hibernate;
        return new LifecyclePolicy(base.idleAfter(), after, base.retryAfter(), config.get(ConfigKeys.PERSISTENCE_AUTOSAVE_INTERVAL), base.hashEvery(),
            config.get(ConfigKeys.PERSISTENCE_KEEP_RECENT), config.get(ConfigKeys.GAME_SIMULATE_WHEN_EMPTY), base.compression(),
            matchTicks(config), config.get(ConfigKeys.PERSISTENCE_RESUME));
    }

    /** A world's {@code persistence.match-checkpoints} in ticks; recording replays needs them, every 10 s unless set. */
    public static int matchTicks(Config config){
        int ticks = LifecyclePolicy.matchTicks(config.get(ConfigKeys.PERSISTENCE_MATCH_CHECKPOINTS));
        return ticks == 0 && config.get(ConfigKeys.REPLAY_RECORD) ? LifecyclePolicy.MATCH_ALIGNMENT : ticks;
    }

    private void instrument(){
        metrics.gauge("shard_sessions", "Live sessions, including ones whose leave a world hasn't taken yet", () -> sessions.size());
        metrics.gauge("shard_players", "Sessions routed to a world", () -> host.players());
        metrics.gauge("shard_worlds", "Hosted worlds", () -> host.worlds().size());
        metrics.gauge("shard_connections", "Open transport connections", () -> transport.connections());
    }

    /** {@code NetworkIO.writeServerData}: what the server list shows. */
    void refreshDiscovery(){
        try{
            Config current = config.current();
            List<HostedWorld> worlds = host.worlds();
            String map = worlds.isEmpty() ? "" : worlds.getFirst().map().name();
            String modeName = worlds.size() > 1 ? worlds.size() + " worlds" : "";
            var buffer = ByteBuffer.allocate(500);
            shortString(buffer, current.get(ConfigKeys.SERVER_NAME), 100);
            shortString(buffer, map, 64);
            buffer.putInt(host.players());
            buffer.putInt(1); // wave: WP46
            buffer.putInt(Admission.BUILD);
            shortString(buffer, Admission.VERSION_TYPE, 32);
            buffer.put((byte)(worlds.isEmpty() ? 0 : worlds.getFirst().mode().ordinal()));
            buffer.putInt(current.get(ConfigKeys.NET_PLAYER_LIMIT));
            shortString(buffer, current.get(ConfigKeys.SERVER_DESCRIPTION), 100);
            shortString(buffer, modeName, 50);
            buffer.putShort((short)transport.address().getPort());
            flow.discovery(Arrays.copyOf(buffer.array(), buffer.position()));
        }catch(RuntimeException e){
            LOG.warn("refreshing the discovery reply failed", e);
        }
    }

    private static void shortString(ByteBuffer buffer, String value, int max){
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if(bytes.length > max) bytes = Arrays.copyOf(bytes, max);
        buffer.put((byte)bytes.length).put(bytes);
    }

    public ConfigService config(){
        return config;
    }

    public Host host(){
        return host;
    }

    public AdminService admin(){
        return admin;
    }

    public Sessions sessions(){
        return sessions;
    }

    /** Chat and its commands: plugins register client commands on {@link Chat#commands()}. */
    public Chat chat(){
        return chat;
    }

    /** Game over and the map rotation. */
    public GameFlow game(){
        return game;
    }

    /** Menus, popups, labels, effects, sounds and plugin packets. */
    public Ui ui(){
        return ui;
    }

    public Transport transport(){
        return transport;
    }

    public ServerThreads threads(){
        return threads;
    }

    public WorldScheduler scheduler(){
        return scheduler;
    }

    public ConnectFlow flow(){
        return flow;
    }

    /**
     * Stops in reverse order: the game flow starts no more reloads, clients are kicked with {@code serverClose} (vanilla's exit closes the server the same
     * way), the transport closes, every world publishes its final checkpoint and hibernates, then the threads, the
     * ops API and the store stop. Safe to call twice.
     */
    @Override
    public synchronized void close(){
        if(closed) return;
        closed = true;
        LOG.info("shutting down");
        timer.shutdownNow();
        // no game-over reload starts from now on, and those under way finish before the worlds hibernate
        game.close();
        for(Session session : sessions.all()) session.conn().closeAfter(Calls.kick(KickReason.SERVER_CLOSE), "server closing");
        // the kicks flush before the transport closes
        long deadline = System.nanoTime() + 2_000_000_000L;
        try{
            while(transport.connections() > 0 && System.nanoTime() < deadline) Thread.sleep(10);
        }catch(InterruptedException e){
            Thread.currentThread().interrupt();
        }
        transport.close();
        Replays recording = replays;
        if(recording != null) recording.closing();
        try{
            manager.close();
        }catch(RuntimeException e){
            LOG.error("worlds didn't all hibernate", e);
        }
        if(recording != null) recording.await(Duration.ofSeconds(60));
        scheduler.close();
        threads.close();
        if(ops != null) ops.close();
        store.close();
        LOG.info("stopped");
    }

    public boolean closed(){
        return closed;
    }

    /** The message of the failure under any completion wrappers. */
    public static String cause(Throwable failure){
        Throwable at = failure;
        while(at instanceof CompletionException && at.getCause() != null) at = at.getCause();
        return at.getMessage() == null ? at.toString() : at.getMessage();
    }
}
