package com.xpdustry.shard.server.sync;

import com.xpdustry.shard.common.geom.Point2;
import com.xpdustry.shard.compat.v160.entity.*;
import com.xpdustry.shard.compat.v160.sync.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.server.intent.*;
import com.xpdustry.shard.server.sched.*;
import com.xpdustry.shard.server.session.*;
import com.xpdustry.shard.sim.building.Building;
import com.xpdustry.shard.sim.building.PassthroughBuilding;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.sim.world.Observer;
import com.xpdustry.shard.wire.*;
import com.xpdustry.shard.wire.v160_7.Packets;

import java.util.*;
import java.util.concurrent.*;

/**
 * One hosted world's sync (WP25, ARCHITECTURE.md §8): vanilla's {@code NetServer.sync} on the world's tick thread,
 * through its {@link Outbox}. It observes every event the world publishes; after each tick the host has it
 * {@linkplain #drain map} them in order with the registered {@link CallMapping}s into Calls, then {@link #endTick}
 * sends what is due, in vanilla's order:
 * <ol>
 *   <li>what other threads {@linkplain Outbox#offer offered};</li>
 *   <li>every {@link SyncSettings#snapshotTicks()}, on the world's staggered phase: the state snapshot, then the
 *   entity snapshots (one set for everyone, or one per team with fog);</li>
 *   <li>every {@link SyncSettings#blockTicks()}: the synced blocks;</li>
 *   <li>building health, at most every {@link SyncSettings#healthTicks()}, and only while a change waits (vanilla
 *   polls its timer only then, so the first change after a quiet spell goes out at once);</li>
 *   <li>every {@link SyncSettings#planTicks()}: each player's plan previews to their teammates;</li>
 *   <li>the {@link Recorder}s due a frame.</li>
 * </ol>
 * The block, health and plan timers run only while someone plays, as vanilla's need a player.
 */
public final class WorldSync{
    private static final System.Logger LOG = System.getLogger(WorldSync.class.getName());
    /** The sync's own observer: a tick's events, all of them. A drop means clients missed Calls. */
    private static final int EVENT_CAPACITY = 1 << 16;

    private final long id;
    private final SyncSettings settings;
    private final Outbox outbox;
    private final BuildingCodecs codecs;
    private final CallMappings mappings;
    /** The attached world's content, and what projects it; the constructor's until a world attaches. Tick thread. */
    private ContentEpoch content;
    private EntitySnapshots entities;
    private BlockSnapshots blocks;
    private final CopyOnWriteArrayList<Recorder> recorders = new CopyOnWriteArrayList<>();
    /** Plan previews by session, assembled from {@link PlanPreviews} batches. */
    private final HashMap<Long, Previews> previews = new HashMap<>();

    // Tick thread only.
    private World world;
    private Observer events;
    private long droppedSeen;
    private HealthUpdates health = new HealthUpdates();
    private long lastHealth = Long.MIN_VALUE / 2, lastPlans = Long.MIN_VALUE / 2;
    private SortedMap<Long, Player> roster = Collections.emptySortedMap();
    private final TreeMap<Integer, Integer> planGroups = new TreeMap<>();
    private final ArrayList<Recording> attached = new ArrayList<>();
    private long skippedSeen, failedCalls;
    private final Context context = new Context();
    private EntityProjector projector;
    private final Sink sink = new Sink();

    /**
     * @param id the world's stagger key ({@code WorldId.hash()})
     */
    public WorldSync(long id, SyncSettings settings, Outbox outbox, ContentEpoch content, BuildingCodecs codecs){
        this(id, settings, outbox, content, codecs, CallMappings.builtIn());
    }

    public WorldSync(long id, SyncSettings settings, Outbox outbox, ContentEpoch content, BuildingCodecs codecs, CallMappings mappings){
        this.id = id;
        this.settings = settings;
        this.outbox = outbox;
        this.codecs = codecs;
        this.mappings = mappings;
        bind(content);
    }

    /**
     * Projects worlds of {@code content}: a map's patched world has its own ({@code Epoch.patched}), with the base's
     * ids, whose passthrough bytes carry its hash, so a building a unit carries there projects only with it.
     */
    private void bind(ContentEpoch content){
        this.content = content;
        this.projector = new EntityProjector(content, codecs);
        this.entities = new EntitySnapshots(projector);
        this.blocks = new BlockSnapshots(content, codecs);
        skippedSeen = 0;
    }

    /** The content the snapshots and Calls project with now: the attached world's. Tick thread. */
    public ContentEpoch content(){
        return content;
    }

    public Outbox outbox(){
        return outbox;
    }

    public SyncSettings settings(){
        return settings;
    }

    /**
     * Adds a recorder; from its first frame boundary on, it sees every frame. Any thread.
     */
    public void record(Recorder recorder){
        if(recorder.frameEvery() < 1) throw new IllegalArgumentException("frameEvery " + recorder.frameEvery());
        recorders.add(recorder);
    }

    /** Removes a recorder; it gets {@link Recorder#detached()} on the tick thread. Any thread. */
    public void stopRecording(Recorder recorder){
        recorders.remove(recorder);
    }

    /**
     * {@code PlayerComp}'s preview plans: batches of one group are assembled, then committed for the teammates once
     * {@link #COMMIT_TICKS} passed since its first batch ({@code getPreviewPlans}, 100 ms), at most
     * {@link #MAX_PREVIEWS} of them.
     */
    private static final class Previews{
        int group = -1;
        boolean receiving;
        long since;
        final ArrayList<com.xpdustry.shard.wire.Codecs.ClientPlan> assembling = new ArrayList<>();
        List<com.xpdustry.shard.wire.Codecs.ClientPlan> current = List.of();

        /** {@code handlePreviewPlans(groupId, plans)}. */
        void batch(long tick, int groupId, List<com.xpdustry.shard.wire.Codecs.ClientPlan> plans){
            if(groupId > group){
                assembling.clear();
                group = groupId;
                receiving = true;
                since = tick;
            }else if(groupId < group || !receiving){
                // out of order, or the group's window has closed
                return;
            }
            if(plans == null) return;
            int added = Math.min(plans.size(), MAX_PREVIEWS - assembling.size());
            if(added > 0) assembling.addAll(plans.subList(0, added));
        }

        /** {@code getPreviewPlans()}. */
        List<com.xpdustry.shard.wire.Codecs.ClientPlan> current(long tick){
            if(receiving && tick - since >= COMMIT_TICKS){
                receiving = false;
                current = List.copyOf(assembling);
                assembling.clear();
            }
            return current;
        }
    }

    /** {@code getPreviewPlans}'s 100 ms commit window, in ticks. */
    static final int COMMIT_TICKS = 6;
    /** {@code Vars.maxPlayerPreviewPlans}. */
    static final int MAX_PREVIEWS = 1000;

    // ---- the tick thread

    /** A new world is resident: its events are observed from now on; its health queue, timers and recorders start over. */
    public void attached(World world){
        this.world = world;
        if(world.content() != content) bind(world.content());
        if(events != null) events.close();
        events = world.observe(EVENT_CAPACITY, List.of(), null);
        droppedSeen = 0;
        health = new HealthUpdates();
        lastHealth = lastPlans = Long.MIN_VALUE / 2;
        planGroups.clear();
        previews.clear();
        for(Recording recording : attached) recording.recorder.detached();
        attached.clear();
        outbox.context(WorldWireContext.capture(world));
    }

    /** The world failed or stopped: recorders are told, nothing more is sent. */
    public void detached(){
        if(events != null) events.close();
        events = null;
        for(Recording recording : attached) recording.recorder.detached();
        attached.clear();
        world = null;
    }

    /** Before the tick's events: who each session's player is. */
    public void beginTick(World world, SortedMap<Long, Player> roster){
        this.world = world;
        this.roster = roster;
        outbox.teams(roster);
    }

    /**
     * The tick's events, in publication order, through {@link #event}.
     *
     * @return whether events were dropped since the last drain: the players missed Calls and need the world again
     */
    public boolean drain(){
        if(events == null) return false;
        for(WorldEvent event : events.drain()) event(event);
        long dropped = events.dropped();
        if(dropped == droppedSeen) return false;
        LOG.log(System.Logger.Level.WARNING, "the sync's observer dropped " + (dropped - droppedSeen) + " events");
        droppedSeen = dropped;
        return true;
    }

    /**
     * One of the tick's events, in publication order: its Calls go out now. A Call that can't be encoded (a config
     * value the wire has no tag for) is left out and logged; it never fails the world's tick.
     */
    public void event(WorldEvent event){
        try{
            switch(event){
                case Outbound outbound -> outbox.send(outbound.audience(), outbound.packet());
                case PlanPreviews batch -> previews.computeIfAbsent(batch.session(), s -> new Previews()).batch(world.ticks(), batch.groupId(), batch.plans());
                case SnapshotRequest request -> snapshot(request);
                default -> mappings.map(event, context, sink);
            }
        }catch(RuntimeException e){
            failedCalls++;
            LOG.log(System.Logger.Level.WARNING, "no Call sent for " + event + ": " + e, e);
        }
        for(Recording recording : attached) recording.events.add(event);
    }

    /**
     * {@code requestBlockSnapshot}'s answer: the building's {@code blockSnapshot}, to the asker. A building gone since,
     * or one Shard only passes through (its bytes are the map's), gets none.
     */
    private void snapshot(SnapshotRequest request){
        Building building = world.buildings().at(Point2.x(request.pos()), Point2.y(request.pos()));
        if(building == null || building instanceof PassthroughBuilding || building.pos() != request.pos()) return;
        outbox.send(Audience.only(request.session()), blocks.one(building));
    }

    /** Events whose Calls couldn't be encoded, since the sync was made. */
    public long failedCalls(){
        return failedCalls;
    }

    /** After the tick's events: snapshots and frames that are due. */
    public void endTick(World world, MapState state){
        long tick = world.ticks();
        outbox.drainOffered();
        boolean playing = outbox.anyPlaying();
        if(playing && Stagger.snapshotDue(id, tick, settings.snapshotTicks())) snapshots(world, state);
        if(playing && Stagger.snapshotDue(id, tick, settings.blockTicks())){
            for(Packets.BlockSnapshot packet : blocks.write(world)) outbox.send(Audience.ALL, packet);
        }
        if(playing && health.pending() && tick - lastHealth >= settings.healthTicks()){
            lastHealth = tick;
            for(Packets.BuildHealthUpdate packet : health.drain(world)) outbox.send(Audience.ALL, packet);
        }
        if(playing && tick - lastPlans >= settings.planTicks()){
            lastPlans = tick;
            plans(world);
        }
        frames(world, tick);
    }

    /** Every entity of {@code Groups.sync} as the players see it now, the state first ({@code NetServer.java:1322}). */
    private void snapshots(World world, MapState state){
        WorldWireContext wire = WorldWireContext.capture(world);
        outbox.context(wire);
        outbox.send(Audience.ALL, GameState.of(world, state, GameState.TPS, settings.universeSeconds()).packet());
        if(world.part(WorldRules.class).rules.fog){
            for(int team : outbox.playingTeams()){
                EntitySnapshots.Team snapshot = entities.team(world, team, wire);
                Audience audience = Audience.team(team);
                for(Packets.EntitySnapshot packet : snapshot.snapshots()) outbox.send(audience, packet);
                if(snapshot.hidden() != null) outbox.send(audience, snapshot.hidden());
            }
        }else{
            List<Packets.EntitySnapshot> packets = entities.all(world, wire);
            // a world with nothing synced still resets the clients' 20 s snapshot timeout (NetClient.java:665)
            if(packets.isEmpty()) outbox.send(Audience.ALL, Calls.EMPTY_ENTITY_SNAPSHOT);
            for(Packets.EntitySnapshot packet : packets) outbox.send(Audience.ALL, packet);
        }
        if(entities.skipped() > skippedSeen){
            LOG.log(System.Logger.Level.WARNING, "snapshots left out " + (entities.skipped() - skippedSeen) + " entities with no projection");
            skippedSeen = entities.skipped();
        }
        for(Session session : outbox.playing()){
            Player player = roster.get(session.id());
            // the view its client last reported in a clientSnapshot (NetConnection.viewX...)
            if(player != null) session.viewer(PlayerInput.viewer(session.id(), player));
        }
    }

    /**
     * {@code NetServer.java:1381-1421}: for each player in {@code Groups.player} order, a new group id and its preview
     * plans, to its teammates: none as one empty packet, else batches of 75.
     */
    private void plans(World world){
        Players players = Players.of(world);
        if(players == null) return;
        var sessions = new HashMap<Player, Long>();
        roster.forEach((session, player) -> sessions.put(player, session));
        previews.keySet().retainAll(roster.keySet());
        for(int i = 0; i < players.list().size(); i++){
            Player player = players.list().get(i);
            Long session = sessions.get(player);
            if(session == null) continue;
            int group = planGroups.merge(player.id(), 1, Integer::sum);
            Audience teammates = new Audience.Team(player.team, session);
            Previews assembled = previews.get(session);
            List<com.xpdustry.shard.wire.Codecs.ClientPlan> plans = assembled == null ? List.of() : assembled.current(world.ticks());
            if(plans.isEmpty()){
                outbox.send(teammates, new Packets.ClientPlanSnapshotReceived(player.id(), group, null));
                continue;
            }
            int chunk = 900 / 12;
            for(int from = 0; from < plans.size(); from += chunk){
                outbox.send(teammates, new Packets.ClientPlanSnapshotReceived(player.id(), group, plans.subList(from, Math.min(plans.size(), from + chunk))));
            }
        }
    }

    private void frames(World world, long tick){
        // recorders join and leave at their own frame boundaries
        for(Recorder recorder : recorders){
            if(tick % recorder.frameEvery() == 0 && attached.stream().noneMatch(r -> r.recorder == recorder)){
                attached.add(new Recording(recorder));
                recorder.attached(world);
            }
        }
        for(Iterator<Recording> it = attached.iterator(); it.hasNext(); ){
            Recording recording = it.next();
            if(!recorders.contains(recording.recorder)){
                it.remove();
                recording.recorder.detached();
            }
        }
        for(Recording recording : attached){
            if(tick % recording.recorder.frameEvery() != 0) continue;
            List<WorldEvent> events = List.copyOf(recording.events);
            recording.events.clear();
            recording.recorder.frame(world, events);
        }
    }

    /** An attached recorder and the events since its last frame. */
    private static final class Recording{
        final Recorder recorder;
        final ArrayList<WorldEvent> events = new ArrayList<>();

        Recording(Recorder recorder){
            this.recorder = recorder;
        }
    }

    private final class Context implements CallContext{
        @Override
        public World world(){
            return world;
        }

        @Override
        public int player(long session){
            Player player = roster.get(session);
            return player == null ? -1 : player.id();
        }

        @Override
        public void healthChanged(int pos){
            health.changed(world, pos);
        }

        @Override
        public EntityProjector projector(){
            return projector;
        }
    }

    private final class Sink implements CallSink{
        @Override
        public void all(Packet packet){
            outbox.send(Audience.ALL, packet);
        }

        @Override
        public void except(long session, Packet packet){
            outbox.send(Audience.except(session), packet);
        }

        @Override
        public void team(int team, Packet packet){
            outbox.send(Audience.team(team), packet);
        }

        @Override
        public void at(float x, float y, Packet packet){
            outbox.send(new Audience.At(x, y), packet);
        }

        @Override
        public void only(long session, Packet packet){
            outbox.send(Audience.only(session), packet);
        }
    }
}
