package com.xpdustry.shard.server.host;

import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.net.*;
import com.xpdustry.shard.server.config.*;
import com.xpdustry.shard.server.host.HostInputs.*;
import com.xpdustry.shard.server.intent.*;
import com.xpdustry.shard.server.sched.*;
import com.xpdustry.shard.server.session.*;
import com.xpdustry.shard.server.sync.*;
import com.xpdustry.shard.server.world.*;
import com.xpdustry.shard.sim.game.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.team.CoreChangeEvent;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.sim.world.Observer;
import com.xpdustry.shard.wire.v160_7.Packets;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * One hosted world: a map in a {@link ManagedWorld}, the {@link WorldMailbox} in front of its inbox, and what runs
 * around its ticks on the tick thread (ARCHITECTURE.md §4, §7). Before each tick it pumps the mailboxes. After it,
 * it reacts to the world's events: it sends the world to a joined player, greets a confirmed one, tells the others
 * when a player leaves and hands a departed one to its transfer; its {@link WorldSync} turns the world's other events
 * into Calls; the {@link HostListener}s hear the tick's intent and game events; then the snapshots that are due go out. Everything it sends goes through its {@link Outbox}
 * (ARCHITECTURE.md §8), which holds a joining player's frames from its stream's capture to the confirm of that load.
 * It never blocks: world streams are deflated on {@code io-cpu}, and sockets are only written through {@link Conn}'s
 * non-blocking sends.
 *
 * <p>World streams: each belongs to a {@link Session.Load}, and goes out only while that load is current
 * ({@link Session#stream}), so a stream deflated for a load a transfer or a {@code /sync} replaced is never sent. A
 * capture reserves its memory first ({@link CaptureBudget}); a load that doesn't fit waits a tick. A load the client
 * doesn't confirm within {@link Host#loadTimeout} is kicked. A stream that fails to capture or encode (a block the
 * writer can't save) kicks the sessions it was for, logged as an error; the world goes on.
 *
 * <p>Lifecycle: joins, confirms and leaves come back as events. Each lifecycle input the world admits publishes at
 * most one of the events it observes, and a tick admits at most an inbox's worth of inputs, so an observer twice the
 * inbox's size can't overflow on them between two of its drains. An intent may publish more (a {@code clientSnapshot}
 * answers each rejected plan with a Call), so it can overflow. Then the host rebuilds the joins, confirms and leaves
 * it missed from the world's members and roster, and sends every member the world again, as {@code /sync} does: a
 * Call it missed (a {@code removeQueueBlock}, a rollback) left its client out of step with the world, and the world's
 * stream puts it back. A member still loading an older stream gets it once it is in the world. Calls that change no
 * state (chat, menus, a kick) stay missed.
 */
public final class HostedWorld implements Destination, WorldHost{
    private static final System.Logger LOG = System.getLogger(HostedWorld.class.getName());
    private static final List<Class<? extends WorldEvent>> EVENTS = List.of(Joined.class, Left.class, Confirmed.class, IntentEvent.class, GameEvent.class,
        CoreChangeEvent.class);
    /** Ticks between checks for loads that took too long. */
    private static final int EXPIRE_TICKS = 60;

    private final Host host;
    private final WorldId id;
    private final MapCatalog.Entry map;
    private final Gamemode mode;
    private final WorldMailbox mailbox;
    private final Outbox outbox;
    private final WorldSync sync;
    private volatile ManagedWorld managed;
    private volatile Capturer capturer;
    private volatile Encoding encoding = WorldSends::encode;
    /** The map's state beside the world (the stream's tags, markers…), which checkpoints don't keep. */
    private volatile MapState state;
    /** The join's asset handshake, or null for a map without external assets ({@link AssetHandshake}). */
    private volatile AssetHandshake assets;
    private final ConcurrentLinkedQueue<Session> syncRequests = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Task<?>> tasks = new ConcurrentLinkedQueue<>();
    private volatile int players;
    /** A world stream's sizes when the map loaded: what a capture reserves. */
    private volatile WorldSends.Footprint footprint = new WorldSends.Footprint(0, 0);
    /** Where streams are deflated: {@code io-cpu} unless a test holds them. */
    private volatile Executor encoder;
    /** The intent layer's decoding state for this world, opaque here: kept beside it so a packet needs no lookup. */
    private volatile Object decoding;

    // Tick thread only.
    private Observer events;
    private long droppedSeen;
    /** Sessions routed here: from their join's admission to their leave's. */
    private final TreeMap<Long, Session> members = new TreeMap<>();
    /** Members whose player is added: they get snapshots and see the others come and go. */
    private final TreeMap<Long, Session> playing = new TreeMap<>();
    /** Members waiting for their world stream (joined, or asked to /sync), in order. */
    private final LinkedHashMap<Long, Session> streams = new LinkedHashMap<>();
    /** Members whose load the client hasn't confirmed: since when, in {@link System#nanoTime}. */
    private final HashMap<Long, Long> loading = new HashMap<>();
    /** Each member's player id, as its join made it. */
    private final HashMap<Long, Integer> playerIds = new HashMap<>();
    /** Members that may have missed a Call the observer dropped: each gets the world again once it is in it. */
    private final LinkedHashMap<Long, Session> stale = new LinkedHashMap<>();
    /** The tick's intent events, for {@link Intents#deliver} once the tick's Calls are out. */
    private final ArrayList<IntentEvent> intents = new ArrayList<>();
    /** The tick's intent, game and core change events, in order, for the {@link HostListener}s after that. */
    private final ArrayList<WorldEvent> heard = new ArrayList<>();
    /** Set by {@link #attached}: check the restored members before the first tick. */
    private boolean restored;

    private record Task<T>(Function<World, T> task, CompletableFuture<T> result){
        void run(World world){
            try{
                result.complete(task.apply(world));
            }catch(RuntimeException e){
                result.completeExceptionally(e);
            }
        }
    }

    HostedWorld(Host host, WorldId id, MapCatalog.Entry map, Gamemode mode, WorldMailbox mailbox, int snapshotTicks){
        this.host = host;
        this.capturer = (world, state, players) -> host.sends().capture(world, state, players);
        this.id = id;
        this.map = map;
        this.mode = mode;
        this.mailbox = mailbox;
        SyncSettings settings = host.sync().withSnapshotTicks(snapshotTicks);
        this.outbox = new Outbox(settings, this::overflowed);
        this.sync = new WorldSync(id.hash(), settings, outbox, host.epoch(), host.codecs());
        // on the offering thread once it holds no lock; inputWaiting itself runs on the server thread
        mailbox.signal(this::wake);
    }

    /** Input waits for the world: wake it to 60 Hz, or from hibernation. Any thread; it doesn't block. */
    private void wake(){
        ManagedWorld current = managed;
        if(current == null) return;
        try{
            host.threads().server().execute(current::inputWaiting);
        }catch(RejectedExecutionException e){
            // shutting down
        }
    }

    public WorldId id(){
        return id;
    }

    public MapCatalog.Entry map(){
        return map;
    }

    public Gamemode mode(){
        return mode;
    }

    /** The managed world, or null while its map loads. */
    public ManagedWorld managed(){
        return managed;
    }

    void managed(ManagedWorld managed){
        this.managed = managed;
    }

    /** What {@link #decoding(Object)} last kept, or null. Any thread. */
    public Object decoding(){
        return decoding;
    }

    /** Keeps the intent layer's decoding state for this world. Any thread; the last write wins. */
    public void decoding(Object state){
        decoding = state;
    }

    void loaded(MapState state, WorldSends.Footprint footprint){
        this.state = state;
        this.assets = AssetHandshake.of(state.assets);
        this.footprint = footprint;
    }

    /** What this world's last capture held: what the next one reserves. */
    WorldSends.Footprint footprint(){
        return footprint;
    }

    /** Deflates this world's streams on {@code executor} instead of {@code io-cpu}; null for {@code io-cpu}. Tests hold them here. */
    void encodeOn(Executor executor){
        this.encoder = executor;
    }

    /** Captures world streams; tests make it fail. */
    interface Capturer{
        List<WorldStreamSource> capture(World world, MapState state, List<Player> players);
    }

    void captureWith(Capturer capturer){
        this.capturer = capturer;
    }

    /** Encodes a captured stream, on {@code io-cpu}; tests hold or fail it. */
    interface Encoding{
        byte[] encode(WorldStreamSource source);
    }

    void encodeWith(Encoding encoding){
        this.encoding = encoding == null ? WorldSends::encode : encoding;
    }

    /** Every frame this world sends goes through it; {@link Outbox#offer} from other threads. */
    public Outbox outbox(){
        return outbox;
    }

    private volatile List<TeamAssigner.Seat> reloaded = List.of();
    /** The world's added players as of its last tick, in PvP only ({@link #seats()}). */
    private volatile List<TeamAssigner.Seat> seats = List.of();

    /** Snapshots, Calls and recorders ({@link WorldSync#record}). */
    public WorldSync sync(){
        return sync;
    }

    /** Sessions routed here, about now. */
    public int players(){
        return players;
    }

    /** Whether a new route may start here: loaded, and not failed or retired. */
    public boolean available(){
        ManagedWorld current = managed;
        return current != null && current.lifecycle() != Lifecycle.FAILED && !mailbox.released() && host.hosts(this);
    }

    @Override
    public String name(){
        return id.name();
    }

    @Override
    public WorldMailbox mailbox(){
        return mailbox;
    }

    @Override
    public Command join(Session session, int generation){
        Identity identity = session.identity();
        List<TeamAssigner.Seat> reload = reloaded;
        return new HostInputs.Join(session.id(), generation, identity.name(), identity.color(), session.admin(), reload.isEmpty() ? null : reload);
    }

    /**
     * The players a map reload moves here ({@link Host#replace}), in the old world's player order with their old
     * teams: each of their joins carries the list, so the world assigns PvP teams as {@code WorldReloader.end} does.
     */
    void reloaded(List<TeamAssigner.Seat> seats){
        reloaded = List.copyOf(seats);
    }

    /**
     * The world's added players at the end of its last tick, in {@code Groups.player} order with their teams, which a
     * reload hands the next map ({@link Host#replace}); empty outside PvP, where a reload gives everyone the default
     * team. Safe from any thread.
     */
    List<TeamAssigner.Seat> seats(){
        return seats;
    }

    /** Refreshes {@link #seats}, building a new list only when a player or a team changed. */
    private void seats(World world){
        Players list = Players.of(world);
        Roster roster = Roster.of(world);
        if(list == null || roster == null || !world.part(WorldRules.class).rules.pvp){
            if(!seats.isEmpty()) seats = List.of();
            return;
        }
        List<TeamAssigner.Seat> current = seats;
        boolean same = current.size() == list.list().size();
        for(int i = 0; same && i < current.size(); i++){
            Player player = list.list().get(i);
            same = current.get(i).team() == player.team && roster.player(current.get(i).session()) == player;
        }
        if(!same) seats = List.copyOf(HostInputs.Join.seats(world, roster));
    }

    @Override
    public Command leave(Session session){
        return new HostInputs.Leave(session.id());
    }

    @Override
    public Intent confirm(Session session, Session.Load load){
        return new HostInputs.Confirm(load.generation());
    }

    /** Vanilla's {@code /sync}: the session gets {@code worldDataBegin} and the world again, after the next tick. */
    public void resync(Session session){
        syncRequests.add(session);
        wake();
    }

    /**
     * Runs {@code task} on the tick thread after the next turn, tick or maintenance, with the world as it is then.
     * Fails if the world isn't in memory.
     */
    public <T> CompletableFuture<T> inspect(Function<World, T> task){
        ManagedWorld current = managed;
        if(current == null || current.lifecycle() != Lifecycle.RUNNING){
            return CompletableFuture.failedFuture(new IllegalStateException(id + " is not running" + (current == null ? "" : " (" + current.lifecycle() + ")")));
        }
        var result = new CompletableFuture<T>();
        tasks.add(new Task<>(task, result));
        return result;
    }

    /**
     * This world as vanilla's {@code SaveIO.save} writes it, for the console's {@code save}: a deflated v13 msav,
     * captured on the tick thread after the next turn. Fails if the world isn't in memory.
     *
     * @param saved the save's {@code saved} meta key, in epoch milliseconds
     */
    public CompletableFuture<byte[]> save(long saved){
        return inspect(world -> host.sends().save(world, state, saved));
    }

    // ---- WorldHost, on the tick thread unless noted

    @Override
    public void attached(World world){
        // a woken or recovered world is a new World: its observers start over, and so do its members
        int capacity = host.eventCapacity();
        events = world.observe(capacity > 0 ? capacity : 2 * world.inbox().capacity(), EVENTS, null);
        droppedSeen = 0;
        members.clear();
        playing.clear();
        streams.clear();
        loading.clear();
        playerIds.clear();
        stale.clear();
        intents.clear();
        heard.clear();
        outbox.clear();
        sync.attached(world);
        AssetHandshake handshake = assets;
        if(handshake != null) handshake.clear();
        mailbox.reopen();
        restored = world.members().size() > 0;
        players = 0;
    }

    @Override
    public void beforeTick(World world){
        if(restored){
            // a recovered world may list sessions whose leaves its failure gave up; not in attached, which holds the
            // managed world's lock, while this takes sessions'
            boolean queued = true;
            for(long session : world.members().sessions()){
                Session live = host.sessions().get(session);
                if(live == null || live.world() != this) queued &= world.inbox().offer(new Submission(new HostInputs.Leave(session), Cause.SERVER));
            }
            // a full inbox takes the rest next tick; a repeated leave is a no-op
            restored = !queued;
        }
        mailbox.pump(world.inbox());
    }

    @Override
    public void afterTick(World world){
        Roster sessions = Roster.of(world);
        sync.beginTick(world, sessions == null ? Collections.emptySortedMap() : sessions.players());
        for(WorldEvent event : events.drain()){
            switch(event){
                case Joined joined -> joined(joined);
                case Confirmed confirmed -> confirmed(confirmed);
                case Left left -> left(left);
                case IntentEvent intent -> {
                    intents.add(intent);
                    heard.add(intent);
                }
                case GameEvent game -> heard.add(game);
                case CoreChangeEvent core -> heard.add(core);
                default -> {
                }
            }
        }
        long dropped = events.dropped();
        if(dropped > droppedSeen){
            reconcile(world);
            stale.putAll(members);
            LOG.log(System.Logger.Level.WARNING, id + ": the host's observer dropped " + (dropped - droppedSeen)
                + " events; rebuilt the joins and leaves from the world, and sending it again to its " + members.size() + " members");
            droppedSeen = dropped;
        }
        // the tick's Calls; the host's own events above only touch their sessions' seats, so the order holds per session
        if(sync.drain()){
            // the players missed Calls: give everyone the world again
            for(Session session : playing.values()) syncRequests.add(session);
        }
        // a kick (or an admin's) after the Calls published before it, as vanilla's player.kick follows what its
        // handler already sent
        for(IntentEvent intent : intents) host.intents().deliver(world, intent);
        intents.clear();
        if(!host.listeners().isEmpty()) listeners(world);
        heard.clear();
        if(!stale.isEmpty()) resyncStale();
        for(Session session; (session = syncRequests.poll()) != null; ){
            if(playing.get(session.id()) == session) reload(session);
        }
        assetsDone();
        if(!streams.isEmpty()) sendWorlds(world);
        if(world.ticks() % EXPIRE_TICKS == 0) expire();
        sync.endTick(world, state);
        runTasks(world);
        players = members.size();
        seats(world);
        // the tick's frames go out: one wake per connection, in the order they were sent (Q215)
        outbox.flush();
    }

    @Override
    public void maintain(World world){
        expire();
        runTasks(world);
        outbox.flush();
    }

    @Override
    public boolean busy(){
        AssetHandshake handshake = assets;
        return mailbox.waiting() > 0 || mailbox.seats() > 0 || !syncRequests.isEmpty() || !tasks.isEmpty() || handshake != null && handshake.hasReady();
    }

    @Override
    public void failed(Throwable failure){
        mailbox.release();
        for(Session session : host.sessions().all()){
            if(session.world() == this || members.containsKey(session.id())){
                session.conn().closeAfter(Calls.kick("[scarlet]This world stopped after an error.[]\nReconnect to join another."), "world " + id + " failed");
            }
        }
        members.clear();
        playing.clear();
        streams.clear();
        loading.clear();
        playerIds.clear();
        stale.clear();
        intents.clear();
        heard.clear();
        outbox.clear();
        sync.detached();
        AssetHandshake handshake = assets;
        if(handshake != null) handshake.clear();
        players = 0;
        for(Task<?> task; (task = tasks.poll()) != null; ) task.result().completeExceptionally(new IllegalStateException(id + " failed", failure));
    }

    /** The tick's events, then the tick itself, to each {@link HostListener}; one that throws doesn't stop the world. */
    private void listeners(World world){
        List<HostListener> listeners = host.listeners();
        for(WorldEvent event : heard){
            for(HostListener listener : listeners){
                try{
                    listener.event(this, world, event);
                }catch(RuntimeException e){
                    LOG.log(System.Logger.Level.ERROR, id + ": " + listener + " failed on " + event, e);
                }
            }
        }
        for(HostListener listener : listeners){
            try{
                listener.ticked(this, world);
            }catch(RuntimeException e){
                LOG.log(System.Logger.Level.ERROR, id + ": " + listener + " failed after tick " + world.ticks(), e);
            }
        }
    }

    private void runTasks(World world){
        for(Task<?> task; (task = tasks.poll()) != null; ) task.run(world);
    }

    // ---- events

    private void joined(Joined joined){
        Session session = host.sessions().get(joined.session());
        // a session that closed or moved on since has its leave queued behind this
        if(session == null || session.routeGeneration() != joined.generation() || session.world() != this) return;
        members.put(session.id(), session);
        playerIds.put(session.id(), joined.player());
        loading.put(session.id(), System.nanoTime());
        outbox.member(session);
        Session.Load load = session.load();
        AssetHandshake handshake = assets;
        if(handshake != null && load != null && load.world() == this && load.kind() != Session.Load.Kind.SYNC){
            // sendWorldAndAssets: the requirements first, the world once the client asks for it
            handshake.begin(session, load);
        }else{
            streams.put(session.id(), session);
        }
        host.log(session + " joins " + id + " as player #" + joined.player() + " (route " + joined.generation() + ")");
    }

    /**
     * {@code requestAssets(ids)} from {@code session} (NetServer.java:963), on the transport's thread: ignored unless
     * its current load of this world waits for it; an empty request asks for the world, any other gets the assets it
     * names as an {@code AssetStream}, written on {@code io-cpu}, once ({@link AssetHandshake}).
     */
    public void requestAssets(Session session, short[] ids){
        AssetHandshake handshake = assets;
        Session.Load load = session.load();
        if(handshake == null || load == null || load.world() != this) return;
        switch(handshake.requestAssets(session, load, ids)){
            case AssetHandshake.Ignored ignored -> {
            }
            case AssetHandshake.SendWorld world -> wake();
            case AssetHandshake.SendAssets send -> {
                Executor executor = encoder != null ? encoder : host.threads().ioCpu();
                try{
                    executor.execute(() -> sendAssets(session, send));
                }catch(RejectedExecutionException e){
                    assetsFailed(session, send.load(), e);
                }
            }
        }
    }

    /** {@code requestWorld} from {@code session} (NetServer.java:955), on the transport's thread: the world, if its load is receiving. */
    public void requestWorld(Session session){
        AssetHandshake handshake = assets;
        Session.Load load = session.load();
        if(handshake == null || load == null || load.world() != this) return;
        if(handshake.requestWorld(session, load)) wake();
    }

    /** On {@code io-cpu}: {@code NetworkIO.writeAssets}, then the stream, unless a newer load fenced it. */
    private void sendAssets(Session session, AssetHandshake.SendAssets send){
        try{
            if(session.load() != send.load()) return;
            byte[] stream = AssetStreams.assets(send.assets());
            if(session.streamAside(send.load(), AssetStreams.ASSET_STREAM, stream)){
                host.log(session + " receives " + send.assets().size() + " assets of " + id + " (" + stream.length + " B)");
            }
        }catch(RuntimeException e){
            assetsFailed(session, send.load(), e);
        }
    }

    private void assetsFailed(Session session, Session.Load load, RuntimeException e){
        if(session.failLoad(load, Calls.kick("[scarlet]The server failed to send you the map's assets.[]\nReport this to its admins."),
            "asset stream failed: " + e)){
            LOG.log(System.Logger.Level.ERROR, id + ": the asset stream for " + session + " failed; kicked it", e);
        }
    }

    /** The loads whose client asked for the world after its assets: their streams go out with this tick's. */
    private void assetsDone(){
        AssetHandshake handshake = assets;
        if(handshake == null) return;
        for(AssetHandshake.Pending done : handshake.ready()){
            Session session = done.session();
            if(members.get(session.id()) == session && session.load() == done.load()) streams.put(session.id(), session);
        }
    }

    /**
     * Captures the world once for every waiting member whose stream fits the {@link CaptureBudget}, then deflates each
     * stream on {@code io-cpu}. The others wait for a later tick.
     */
    private void sendWorlds(World world){
        Roster roster = Roster.of(world);
        var sessions = new ArrayList<Session>(streams.size());
        var loads = new ArrayList<Session.Load>(streams.size());
        var players = new ArrayList<Player>(streams.size());
        for(Session session : streams.values()){
            Player player = roster.player(session.id());
            Session.Load load = session.load();
            // a load fenced since (the session moved on or closed) needs no stream
            if(player == null || members.get(session.id()) != session || load == null || load.world() != this) continue;
            sessions.add(session);
            loads.add(load);
            players.add(player);
        }
        streams.clear();
        if(sessions.isEmpty()) return;
        // the last capture's footprint, then what this one turns out to hold
        CaptureBudget budget = host.captureBudget();
        WorldSends.Footprint expected = footprint;
        if(tooLarge(expected, budget, sessions, loads)) return;
        var budgets = new ArrayList<CaptureBudget.Stream>(sessions.size());
        CaptureBudget.Capture capture = budget.capture(this, expected.capture());
        if(capture != null){
            for(int i = 0; i < sessions.size(); i++){
                CaptureBudget.Stream stream = capture.add(expected.stream());
                if(stream == null) break;
                budgets.add(stream);
            }
        }
        for(int i = budgets.size(); i < sessions.size(); i++) streams.put(sessions.get(i).id(), sessions.get(i));
        if(budgets.isEmpty()){
            if(capture != null) capture.close();
            return;
        }
        List<WorldStreamSource> sources;
        try{
            sources = capturer.capture(world, state, players.subList(0, budgets.size()));
        }catch(RuntimeException e){
            // a world the writer can't capture fails these loads, not the world
            for(int i = 0; i < budgets.size(); i++){
                budgets.get(i).release();
                streamFailed(sessions.get(i), loads.get(i), "capturing", e);
            }
            capture.close();
            return;
        }
        WorldSends.Footprint actual = WorldSends.Footprint.of(sources.getFirst());
        footprint = actual;
        if(tooLarge(actual, budget, sessions.subList(0, budgets.size()), loads) || !capture.resize(actual.capture(), actual.stream())){
            // it grew past what fits now: unsent, and the loads wait for a reservation of the new size
            for(CaptureBudget.Stream stream : budgets) stream.release();
            capture.close();
            for(int i = 0; i < budgets.size(); i++) streams.put(sessions.get(i).id(), sessions.get(i));
            return;
        }
        capture.close();
        Executor executor = encoder != null ? encoder : host.threads().ioCpu();
        for(int i = 0; i < budgets.size(); i++){
            Session session = sessions.get(i);
            Session.Load load = loads.get(i);
            WorldStreamSource source = sources.get(i);
            CaptureBudget.Stream reservation = budgets.get(i);
            // from the capture on, the session's frames wait for this load's confirm
            outbox.capture(session, load.generation());
            try{
                executor.execute(() -> encode(session, load, source, reservation));
            }catch(RejectedExecutionException e){
                // io-cpu is full: try again after the next tick, a new capture
                reservation.release();
                streams.put(session.id(), session);
            }
        }
    }

    /** On {@code io-cpu}: deflates {@code load}'s stream and queues it, unless a newer load fenced it meanwhile. */
    private void encode(Session session, Session.Load load, WorldStreamSource source, CaptureBudget.Stream budget){
        try{
            // fenced while it waited: nothing to deflate
            if(session.load() != load) return;
            byte[] stream = encoding.encode(source);
            if(outbox.stream(session, load, stream)) host.log(session + " receives " + id + " (" + stream.length + " B)");
        }catch(RuntimeException e){
            streamFailed(session, load, "encoding", e);
        }finally{
            budget.release();
        }
    }

    /**
     * Kicks a session whose world stream for {@code load} failed, a server bug, so an error; unless a transfer or a
     * {@code /sync} replaced that load meanwhile, which the failure then doesn't touch.
     */
    private void streamFailed(Session session, Session.Load load, String step, RuntimeException e){
        if(session.failLoad(load, Calls.kick("[scarlet]The server failed to send you the world.[]\nReport this to its admins."),
            "world stream failed: " + e)){
            LOG.log(System.Logger.Level.ERROR, id + ": " + step + " the world stream for " + session + " failed; kicked it", e);
        }else{
            LOG.log(System.Logger.Level.WARNING, id + ": " + step + " the world stream for " + session + " failed, after a newer load replaced it", e);
        }
    }

    /**
     * Whether a capture with this footprint can never be sent ({@link CaptureBudget#captureLimit}); if so, the loads
     * fail, an error.
     */
    private boolean tooLarge(WorldSends.Footprint footprint, CaptureBudget budget, List<Session> sessions, List<Session.Load> loads){
        if(footprint.capture() + footprint.stream() <= budget.captureLimit()) return false;
        var e = new IllegalStateException(id + " needs " + (footprint.capture() + footprint.stream()) + " B to send, over the "
            + budget.captureLimit() + " B a capture may hold");
        for(int i = 0; i < sessions.size(); i++) streamFailed(sessions.get(i), loads.get(i), "sizing", e);
        return true;
    }

    /** Kicks the members whose load the client hasn't confirmed within {@link Host#loadTimeout}. */
    private void expire(){
        if(loading.isEmpty()) return;
        long now = System.nanoTime(), timeout = host.loadTimeout().toNanos();
        for(var it = loading.entrySet().iterator(); it.hasNext(); ){
            var entry = it.next();
            if(now - entry.getValue() < timeout) continue;
            it.remove();
            Session session = members.get(entry.getKey());
            if(session == null) continue;
            session.conn().closeAfter(Calls.kick("[scarlet]Loading the world took too long."), "load timed out");
            host.log(session + " kicked: loading " + id + " took over " + host.loadTimeout().toSeconds() + " s");
        }
    }

    /** Sends the world again to the stale members in it, as {@code /sync} does; the others wait, or need nothing. */
    private void resyncStale(){
        for(var it = stale.values().iterator(); it.hasNext(); ){
            Session session = it.next();
            // gone, or its stream isn't captured yet: that capture comes after the missed Calls
            if(members.get(session.id()) != session || streams.containsKey(session.id())){
                it.remove();
                continue;
            }
            // still loading a stream captured before them: once it's in the world
            if(reload(session)) it.remove();
        }
    }

    /** Vanilla's {@code /sync} for a session in this world: {@code worldDataBegin}, then the world after this tick. */
    private boolean reload(Session session){
        if(session.reload(Calls.WORLD_DATA_BEGIN) == null) return false;
        outbox.reloading(session);
        loading.put(session.id(), System.nanoTime());
        streams.put(session.id(), session);
        return true;
    }

    /**
     * The observer dropped events: brings the members, the players and the waiting streams back in line with the
     * world, as the missed {@link Joined}, {@link Confirmed} and {@link Left} events would have. Missed chat stays
     * missed.
     */
    private void reconcile(World world){
        Roster roster = Roster.of(world);
        for(long session : List.copyOf(members.keySet())){
            if(!world.members().contains(session)){
                Integer player = playerIds.get(session);
                left(new Left(session, player == null ? -1 : player, playing.containsKey(session) && player != null));
            }
        }
        for(long session : world.members().sessions()){
            Player player = roster.player(session);
            if(player == null) continue;
            if(!members.containsKey(session)){
                joined(new Joined(session, world.members().get(session).orElseThrow().routeGeneration(), player.id()));
            }
            Session live = members.get(session);
            if(live != null && player.isAdded() && !playing.containsKey(session)) confirmed(new Confirmed(session, player.id(), live.loadGeneration()));
        }
    }

    private void confirmed(Confirmed confirmed){
        Session session = members.get(confirmed.session());
        if(session == null) return;
        // what happened since this load's capture, in order, before anything else
        int flushed = outbox.confirmed(session.id(), confirmed.load());
        if(flushed > 0) host.log(session + " catches up on " + flushed + " frames");
        loading.remove(session.id());
        playing.put(session.id(), session);
        if(!host.firstConfirm(session)) return;
        // vanilla greets a connection once (NetServer.java:1006), not on every world it moves to
        Config config = host.config(id);
        String name = session.identity().name();
        if(config.get(ConfigKeys.CHAT_SHOW_CONNECT_MESSAGES)){
            broadcast(Calls.message("[accent]" + name + "[accent] has connected."));
        }
        String motd = config.get(ConfigKeys.SERVER_MOTD);
        if(!motd.isEmpty()) outbox.send(Audience.only(session.id()), Calls.message(motd));
        host.log(session + " has connected to " + id + " [" + session.identity().uuid() + "]");
    }

    private void left(Left left){
        Session session = members.remove(left.session());
        playing.remove(left.session());
        streams.remove(left.session());
        loading.remove(left.session());
        playerIds.remove(left.session());
        AssetHandshake handshake = assets;
        if(handshake != null) handshake.forget(left.session());
        outbox.left(left.session());
        if(left.confirmed()) broadcast(Calls.playerDisconnect(left.player()));
        if(session == null) session = host.sessions().get(left.session());
        if(session == null) return;
        if(host.departed(session, this)) return;
        if(left.confirmed() && session.state() == SessionState.CLOSED && host.config(id).get(ConfigKeys.CHAT_SHOW_CONNECT_MESSAGES)){
            broadcast(Calls.message("[accent]" + session.identity().name() + "[accent] has disconnected."));
        }
    }

    /** To everyone here; a joining player gets it once its load is confirmed. */
    private void broadcast(Frame frame){
        outbox.send(Audience.ALL, frame);
    }

    /**
     * A joining player's held frames went over the bound: what it needs to catch up is gone. It is disconnected; a new
     * load would do too, but a busy world could fill that one as well.
     */
    private void overflowed(Session session, int load){
        LOG.log(System.Logger.Level.WARNING, id + ": " + session + "'s join buffer overflowed (load " + load + ")");
        session.conn().closeAfter(Calls.kick("[scarlet]The world changed too much while you were joining.[]\nPlease join again."), "join buffer overflow");
    }

    /** The world's rules as hosted, for status lines; on any thread, it reads the map's preset. */
    public String describe(){
        ManagedWorld current = managed;
        WorldStatus status = current == null ? null : current.status();
        return id + ": " + map.name() + " (" + mode.name().toLowerCase(Locale.ROOT) + "), "
            + (status == null ? "loading" : status.lifecycle().name().toLowerCase(Locale.ROOT) + " " + status.mode().name().toLowerCase(Locale.ROOT)
            + ", tick " + status.tick()) + ", " + players + " players";
    }

    @Override
    public String toString(){
        return "HostedWorld[" + id + "]";
    }
}
