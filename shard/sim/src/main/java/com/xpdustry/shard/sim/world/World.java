package com.xpdustry.shard.sim.world;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.bullet.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;

import java.util.*;
import java.util.function.*;

/**
 * One simulated world: the root that owns everything it simulates (ARCHITECTURE.md §3, §4). Exactly one thread at
 * a time calls into it; other threads reach it only through its {@link Inbox}. Nothing it owns points outside it,
 * which is what makes {@link #fork()} and checkpoints possible.
 *
 * <p>What it owns, and how checkpoints treat it ({@link Persistence}):
 * <ul>
 *   <li>authoritative: the tick counter, the gameplay {@link Rand}, the {@link Timers}, the {@link EntityIds}
 *   counter, the {@link TileGrid}, the {@link Buildings} registry with its building objects, the update lists
 *   ({@link #builds()}, {@link #units()}) in order, {@link Bullets}, {@link Members}, the {@link CommandLog}'s
 *   position, the {@link Journal} and every authoritative {@link WorldPart};</li>
 *   <li>derived: {@link Persistence#DERIVED} parts, rebuilt on restore;</li>
 *   <li>external: the inbox and observers, which a fork doesn't copy.</li>
 * </ul>
 *
 * <p>{@link #tick()} advances exactly Δ = 1: it admits queued inputs, advances the timers, runs every
 * {@link Phase}'s handlers in order, then publishes the tick's events to observers. The sim never reads a clock.
 * {@code sim.persist} stores and restores worlds; it builds a restored one through {@link #restoring(WorldConfig)}.
 *
 * <p>Owner: WP13 (tick, timers, scheduling hooks). Tables and objects belong to their packages' owners.
 */
public final class World{
    /** {@link Phase#values()}, which clones its array on every call. */
    private static final Phase[] PHASES = Phase.values();
    /** {@link #found}'s first size: room for the few dozen types hot paths look up, at most half full. */
    private static final int FOUND_SLOTS = 64;

    private final WorldConfig config;
    private final Rand rand;
    private Timers timers;
    private final EntityIds ids;
    private final TileGrid tiles;
    private final UnitTable unitTable;
    private Buildings buildings;
    private ActiveList<Building> builds;
    private ActiveList<Unit> units;
    private Bullets bullets;
    private final Members members;
    private CommandLog log;
    private Journal journal;
    private List<WorldPart> parts;
    /**
     * {@link #find}'s answers, null ones included, as (type, part) pairs in an open-addressed table of a power of two
     * slots: dropped when {@link #parts} change. Replaced whole, never written in place, so a lookup from another
     * thread sees one consistent table.
     */
    @Derived("find's answers, looked up again after a restore")
    private volatile Object[] found = new Object[FOUND_SLOTS * 2];
    /** {@link #hash()}'s last tiles section and the {@link TileGrid#version()} it hashed. */
    @Derived("hash()'s cache of the tiles' section, hashed again on first use")
    private WorldHash.Section tilesHash;
    @Derived("with tilesHash")
    private long tilesHashVersion;
    private final Inbox inbox;
    private volatile Subscribers subscribers = Subscribers.NONE;
    private final Object subscribing = new Object();
    private boolean ticking;
    private long ticks;
    /** {@code state.isPaused()}: see {@link #paused()}. */
    private boolean paused;
    /** {@link #frozen()}. */
    @Derived("tick: paused, taken once COMMANDS ran")
    private boolean tickFrozen;

    public World(WorldConfig config){
        this(config, false);
        var parts = new ArrayList<WorldPart>();
        for(Class<? extends WorldPart> type : config.systems().parts()) parts.add(config.systems().create(type));
        parts(List.copyOf(parts));
        for(WorldPart part : this.parts) part.init(this);
    }

    /** A world with empty tables and no parts: the start of a new world, or of a restored one. */
    private World(WorldConfig config, boolean restoring){
        config.kinds().requireSupported(config.content());
        this.config = config;
        this.rand = new Rand(config.seed());
        this.timers = new Timers();
        this.ids = new EntityIds();
        this.tiles = new TileGrid(config.width(), config.height(), BlockTable.of(config.content()));
        this.unitTable = UnitTable.of(config.content());
        this.buildings = new Buildings(this);
        this.builds = new ActiveList<>(Building.updateSlot());
        this.units = new ActiveList<>(Unit.updateSlot());
        this.bullets = new Bullets(ids);
        this.members = new Members();
        this.log = new CommandLog();
        this.journal = config.journal().emptyAt(0);
        this.inbox = new Inbox(config.inboxCapacity());
        this.parts = restoring ? null : List.of();
    }

    /** The fork constructor: everything but the object graph and the parts, which {@link #fork()} copies afterwards. */
    private World(World source){
        this.config = source.config;
        this.rand = source.rand.copy();
        this.ids = source.ids.copy();
        this.tiles = source.tiles.copy();
        this.unitTable = source.unitTable;
        this.members = source.members.copy();
        this.log = source.log.copy();
        this.journal = source.journal.copy();
        this.inbox = new Inbox(source.inbox.capacity());
        this.ticks = source.ticks;
        this.paused = source.paused;
    }

    /**
     * {@code state.isPaused()}: a paused world's {@link #tick()} admits its inputs and runs {@link Phase#COMMANDS},
     * {@link Phase#GAME_STATE} and {@link Phase#SYNC}, plus the handlers that run while paused
     * ({@link PhaseHandler#runsWhilePaused()}: vanilla's posted tasks and enemy count, which come before
     * {@code Logic.update}'s {@code isPaused} check). Everything else stands still, the {@link #timers()} and their game
     * time included. {@link #ticks()} still counts: it numbers the world's turns (the command log, sync cadence), and
     * its gameplay readers stand in for vanilla's wall clock, which a pause doesn't stop. Hashed only while set, and
     * checkpointed.
     */
    public boolean paused(){
        return paused;
    }

    /**
     * Whether the tick in progress runs paused: {@link #paused()} as it was once {@link Phase#COMMANDS} ran, as
     * vanilla's frame runs its posted tasks (packets, console commands) and {@code Trigger.update} before
     * {@code Logic.update}'s {@code isPaused} check. A handler that {@link PhaseHandler#runsWhilePaused() runs while
     * paused} reads it; during {@link Phase#COMMANDS} it is the last tick's.
     */
    public boolean frozen(){
        return tickFrozen;
    }

    /**
     * Pauses or resumes the world: vanilla's {@code state.set(State.paused)} or {@code playing}. Set between ticks or
     * during {@link Phase#COMMANDS}, it holds for the tick; set later, from the next tick.
     */
    public void paused(boolean paused){
        this.paused = paused;
    }

    /** Runs one tick; see {@link #tick(TickProbe)}. */
    public void tick(){
        tick(TickProbe.NONE);
    }

    /**
     * Runs one tick: admission at the start of {@link Phase#COMMANDS}, the timers at the start of {@link Phase#TIME},
     * each phase's handlers in order, then the tick's events go to observers. {@code probe} sees each phase that has
     * work. If a handler throws, the tick's events are discarded: observers only see committed ticks.
     */
    public void tick(TickProbe probe){
        if(ticking) throw new IllegalStateException("tick() called from inside a tick");
        ticking = true;
        tiles.beginTick();
        try{
            Systems systems = config.systems();
            boolean frozen = tickFrozen;
            for(Phase phase : PHASES){
                if(phase == Phase.PATH_FLUSH){
                    // the posted tasks and Trigger.update came first: the rest of the tick runs as they left it
                    frozen = paused;
                    tickFrozen = frozen;
                }
                List<PhaseHandler> handlers = systems.handlers(phase);
                if(handlers.isEmpty() && phase != Phase.COMMANDS && phase != Phase.TIME) continue;
                boolean whole = !frozen || phase == Phase.COMMANDS || phase == Phase.GAME_STATE || phase == Phase.SYNC;
                probe.enter(phase);
                if(phase == Phase.COMMANDS) admit();
                else if(phase == Phase.TIME && !frozen) timers.update(this);
                for(PhaseHandler handler : handlers){
                    if(whole || handler.runsWhilePaused()) handler.run(this);
                }
            }
            probe.exit();
        }catch(Throwable error){
            for(Observer observer : subscribers.observers) observer.discard();
            throw error;
        }finally{
            tiles.endTick();
            ticking = false;
        }
        ticks++;
        deliver();
    }

    /** Drains the inbox in arrival order, applies each input and logs it with its verdict. */
    private void admit(){
        inbox.drain(arrival -> {
            Verdict verdict = switch(arrival.input()){
                case IntentEnvelope envelope -> {
                    Verdict refused = members.check(envelope);
                    yield refused != null ? refused : envelope.payload().apply(this, envelope);
                }
                case Submission submission -> submission.command().apply(this, submission.cause());
            };
            log.append(new CommandLog.Entry(ticks, arrival.number(), arrival.input(), Objects.requireNonNull(verdict, "verdict")));
        });
    }

    /** Hands the committed tick's events to every open observer, then forgets closed ones. */
    private void deliver(){
        Subscribers current = subscribers;
        boolean closed = false;
        for(Observer observer : current.observers){
            if(observer.closed()){
                observer.discard();
                closed = true;
            }else{
                observer.commit();
            }
        }
        if(closed) unsubscribeClosed();
    }

    /** Whether this world is between ticks, where forks, checkpoints and hashes are taken. */
    public boolean atBoundary(){
        return !ticking;
    }

    /**
     * A deep copy at this tick boundary that continues bit for bit like this world, with an empty inbox and no
     * observers. The two share only immutable things: the config, content and log entries.
     */
    public World fork(){
        if(ticking) throw new IllegalStateException("fork only between ticks");
        var fork = new World(this);
        var context = new CopyContext(fork);
        fork.buildings = buildings.copy(context);
        fork.builds = builds.copy(context);
        fork.units = units.copy(context);
        fork.bullets = bullets.copy(context);
        fork.timers = timers.copy(context);
        var forkedParts = new ArrayList<WorldPart>(parts.size());
        for(WorldPart part : parts) forkedParts.add(part.copy(context));
        fork.parts(List.copyOf(forkedParts));
        context.finish();
        for(WorldPart part : fork.parts) part.forked(fork);
        return fork;
    }

    /** The world's state hash, one section per subsystem. */
    public WorldHash hash(){
        var sections = new ArrayList<WorldHash.Section>();
        sections.add(section("clock", h -> {
            h.l(ticks).l(rand.getState(0)).l(rand.getState(1));
            ids.hash(h);
            // only a paused world hashes the flag, so others keep their hashes
            if(paused) h.bool(true);
        }));
        sections.add(section("timers", timers::hash));
        sections.add(tilesSection());
        sections.add(section("buildings", buildings::hash));
        sections.add(section("builds", h -> {
            h.i(builds.size());
            for(int i = 0; i < builds.size(); i++) h.i(builds.get(i).index());
        }));
        sections.add(section("units", h -> {
            h.i(units.size());
            for(int i = 0; i < units.size(); i++) units.get(i).hashState(h);
        }));
        sections.add(section("bullets", bullets::hash));
        sections.add(section("members", members::hash));
        for(WorldPart part : parts){
            if(part.persistence() == Persistence.AUTHORITATIVE) sections.add(section(part.getClass().getName(), part::hash));
        }
        return WorldHash.of(sections);
    }

    /**
     * The tiles' section, hashed again only once the grid changed ({@link TileGrid#version()}): a hosted world hashes
     * every 300 ticks for its crash bundle, and its tiles, unchanged nearly every time, were 80% of that (W2.21).
     */
    private WorldHash.Section tilesSection(){
        long version = tiles.version();
        if(tilesHash == null || tilesHashVersion != version){
            tilesHash = section("tiles", tiles::hash);
            tilesHashVersion = version;
        }
        return tilesHash;
    }

    private static WorldHash.Section section(String name, Consumer<Hasher> feed){
        var hasher = new Hasher();
        feed.accept(hasher);
        return new WorldHash.Section(name, hasher.value());
    }

    /**
     * Queues an event for the observers that want it, delivered after this tick commits. Events are immutable. Each
     * observer holds at most its queue's capacity per tick and counts the rest as dropped, so a flood of one type
     * never pushes out another observer's events.
     */
    public void publish(WorldEvent event){
        Objects.requireNonNull(event, "event");
        for(Observer observer : subscribers.observers){
            if(!observer.closed()) observer.stage(event);
        }
    }

    /** A new observer of every event, queueing at most {@code capacity}. Safe from any thread. */
    public Observer observe(int capacity){
        return observe(capacity, List.of(), null);
    }

    /**
     * A new observer queueing at most {@code capacity} events of {@code types} (empty for all). {@code wake}, if not
     * null, runs on the world thread after a tick delivered something to it, and must not block. Safe from any
     * thread; it sees the events published after this call.
     */
    public Observer observe(int capacity, List<Class<? extends WorldEvent>> types, Runnable wake){
        var observer = new Observer(capacity, types, wake);
        synchronized(subscribing){
            Observer[] current = subscribers.observers;
            Observer[] next = Arrays.copyOf(current, current.length + 1);
            next[current.length] = observer;
            subscribers = new Subscribers(next);
        }
        return observer;
    }

    private void unsubscribeClosed(){
        synchronized(subscribing){
            subscribers = new Subscribers(Arrays.stream(subscribers.observers).filter(o -> !o.closed()).toArray(Observer[]::new));
        }
    }

    /** Queues a command from {@code cause}; false if the inbox is full. Safe from any thread. */
    public boolean submit(Command command, Cause cause){
        return inbox.offer(new Submission(command, cause));
    }

    /** The part of class {@code type}. */
    public <T extends WorldPart> T part(Class<T> type){
        // find's first instance is the first part of exactly this class whenever its class is this one
        T found = find(type);
        if(found != null && found.getClass() == type) return found;
        for(WorldPart part : parts){
            if(part.getClass() == type) return type.cast(part);
        }
        throw new NoSuchElementException(type.getName() + " is not a part of this world");
    }

    /**
     * The first part that is a {@code type}, a class or an interface, in {@link #parts()} order, or null. Each type is
     * looked up once per world, so hot paths ({@code TeamUnits.of} on every unit add) can call it freely.
     */
    public <T> T find(Class<T> type){
        Object[] table = found;
        int mask = (table.length >>> 1) - 1;
        for(int slot = slot(type) & mask; ; slot = (slot + 1) & mask){
            Object key = table[slot << 1];
            if(key == type) return type.cast(table[(slot << 1) + 1]);
            if(key == null) break;
        }
        if(parts == null) throw new IllegalStateException("the restored world has no parts yet");
        Object part = null;
        for(WorldPart candidate : parts){
            if(type.isInstance(candidate)){
                part = candidate;
                break;
            }
        }
        int used = 1;
        for(int i = 0; i < table.length; i += 2) if(table[i] != null) used++;
        // at most half full, so a lookup that misses meets an empty slot
        Object[] grown = new Object[used * 2 > (table.length >>> 1) ? table.length * 2 : table.length];
        for(int i = 0; i < table.length; i += 2) if(table[i] != null) put(grown, table[i], table[i + 1]);
        put(grown, type, part);
        found = grown;
        return type.cast(part);
    }

    /** A type's first slot in {@link #found}: from its name, so the table's layout is the same on every run. */
    private static int slot(Class<?> type){
        int hash = type.getName().hashCode();
        return hash ^ hash >>> 16;
    }

    private static void put(Object[] table, Object type, Object part){
        int mask = (table.length >>> 1) - 1, slot = slot((Class<?>)type) & mask;
        while(table[slot << 1] != null) slot = (slot + 1) & mask;
        table[slot << 1] = type;
        table[(slot << 1) + 1] = part;
    }

    private void parts(List<WorldPart> parts){
        this.parts = parts;
        found = new Object[FOUND_SLOTS * 2];
    }

    /** Every part, sorted by class name. */
    public List<WorldPart> parts(){
        return parts;
    }

    public WorldConfig config(){
        return config;
    }

    public ContentEpoch content(){
        return config.content();
    }

    public KindRegistry kinds(){
        return config.kinds();
    }

    /** The gameplay random generator. Nothing else in the sim draws randomness. */
    public Rand rand(){
        return rand;
    }

    /** Vanilla's {@code Time}: game time and delayed tasks. */
    public Timers timers(){
        return timers;
    }

    /** Ticks completed since the world was created. */
    public long ticks(){
        return ticks;
    }

    public EntityIds ids(){
        return ids;
    }

    public TileGrid tiles(){
        return tiles;
    }

    /** The parameters of the content's unit types. */
    public UnitTable unitTable(){
        return unitTable;
    }

    /** Vanilla's {@code world.tile(x, y)}: the tile at (x, y), or null outside the map. */
    public Tile tile(int x, int y){
        return Tile.at(this, x, y);
    }

    public Buildings buildings(){
        return buildings;
    }

    /** Vanilla's {@code Groups.build}: the building update list. */
    public ActiveList<Building> builds(){
        return builds;
    }

    /** Vanilla's {@code Groups.unit}. */
    public ActiveList<Unit> units(){
        return units;
    }

    public Bullets bullets(){
        return bullets;
    }

    public Members members(){
        return members;
    }

    public Inbox inbox(){
        return inbox;
    }

    public CommandLog log(){
        return log;
    }

    public Journal journal(){
        return journal;
    }

    /**
     * Starts restoring a world of {@code config} from stored state. Only {@code sim.persist} should call this: it
     * fills the world's tables in place, hands over the objects and parts it read, then {@link Restoring#finish()}es.
     * No part is {@link WorldPart#init(World) initialized}; the reader does what each part's persistence asks.
     */
    public static Restoring restoring(WorldConfig config){
        return new World(config, true).new Restoring();
    }

    /** The open end of a world being restored ({@link #restoring(WorldConfig)}). Unusable once finished. */
    public final class Restoring{
        private boolean done;

        private Restoring(){
        }

        /** The world being restored. Its tables exist, empty; fill them in place. */
        public World world(){
            check();
            return World.this;
        }

        public void ticks(long ticks){
            check();
            World.this.ticks = ticks;
        }

        public void paused(boolean paused){
            check();
            World.this.paused = paused;
        }

        public void timers(double time, List<Timers.Pending> pending){
            check();
            timers.restore(time, pending);
        }

        public void member(long session, Members.Member member){
            check();
            members.restore(session, member);
        }

        /** The command log's position at the checkpoint; the restored log continues from there. */
        public void logPosition(long position){
            check();
            log = new CommandLog(position);
        }

        public void journal(Journal journal){
            check();
            World.this.journal = Objects.requireNonNull(journal, "journal");
        }

        /** The update lists and bullets; the building registry is {@link World#buildings()}, filled in place. */
        public void objects(ActiveList<Building> builds, ActiveList<Unit> units, Bullets bullets){
            check();
            World.this.builds = Objects.requireNonNull(builds, "builds");
            World.this.units = Objects.requireNonNull(units, "units");
            World.this.bullets = Objects.requireNonNull(bullets, "bullets");
        }

        /** Every part of the config's systems, in its order. */
        public void parts(List<WorldPart> parts){
            check();
            var expected = config.systems().parts();
            if(parts.size() != expected.size()) throw new IllegalArgumentException("expected parts " + expected + ", got " + parts);
            for(int i = 0; i < parts.size(); i++){
                if(parts.get(i).getClass() != expected.get(i)) throw new IllegalArgumentException("part " + i + " should be " + expected.get(i).getName());
            }
            World.this.parts(List.copyOf(parts));
        }

        /** The restored world, ready to tick. */
        public World finish(){
            check();
            if(World.this.parts == null) throw new IllegalStateException("the restored world has no parts yet");
            done = true;
            return World.this;
        }

        private void check(){
            if(done) throw new IllegalStateException("this world is already restored");
        }
    }

    /** The open observers, replaced whole when one subscribes or closes. */
    private record Subscribers(Observer[] observers){
        static final Subscribers NONE = new Subscribers(new Observer[0]);
    }
}
