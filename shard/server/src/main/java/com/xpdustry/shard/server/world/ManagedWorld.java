package com.xpdustry.shard.server.world;

import com.xpdustry.shard.server.sched.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * One world under the {@link WorldManager}: its lifecycle (ARCHITECTURE.md §7), its checkpoints and its crash
 * bundles.
 *
 * <ul>
 *   <li>A running world with no members keeps ticking for {@link LifecyclePolicy#idleAfter}, then drops to 1 Hz
 *   maintenance with simulation time frozen (unless {@link LifecyclePolicy#continuous}). Any input wakes it to
 *   60 Hz, and so does {@link #inputWaiting} from input queued in front of the inbox.</li>
 *   <li>Every {@link LifecyclePolicy#autosaveEvery} it publishes a generation: a fork on the tick thread (the
 *   shortest pause, see {@code CheckpointBench}), encoding on {@code io-cpu}, publication on {@code io}. Once
 *   durable, the command log is trimmed to the generation's position.</li>
 *   <li>With the match checkpoint interval (the world's own, {@link LifecyclePolicy#matchCheckpointEvery} by default), it also writes a match checkpoint at every multiple of it,
 *   right after that tick (after {@code SYNC}, before the next {@code COMMANDS}), kept for the whole match. A restore
 *   drops those from after the restored tick: they belong to a timeline the world no longer runs.</li>
 *   <li>A match is one game in this world: it starts when a fresh world opens, and its id goes in every checkpoint's
 *   {@code match} label, so a wake, a recovery or a resumed server goes on with it. The {@link MatchListener} hears
 *   when the world becomes resident and when a match checkpoint is durable.</li>
 *   <li>A checkpoint stores the inputs waiting in the inbox with their arrival numbers: a capture of a fork takes the
 *   live inbox's {@linkplain Inbox#snapshot() snapshot} at the fork's tick.</li>
 *   <li>Empty for {@link LifecyclePolicy#hibernateAfter}, it quiesces: the inbox is sealed, the slot removed and a
 *   final generation published. Only once that generation is durable and current does the world leave memory. If
 *   publication fails it stays resident on the last good generation and retries after
 *   {@link LifecyclePolicy#retryAfter}. A join while quiescing cancels it; the final save still finishes as an
 *   ordinary generation, unless a newer one was published first: the store refuses a capture older than the current
 *   generation, and the world reports it.</li>
 *   <li>Waking is single-flight: every caller shares one restore.</li>
 *   <li>A tick that throws fails the world: it writes a crash bundle (the last durable generation, the log since it,
 *   the hash trail and the error) for {@code shard replay}, and leaves memory. {@link #recover()} restores the last
 *   durable generation.</li>
 * </ul>
 *
 * Its {@link WorldHost} runs around each tick on the tick thread; a host that is {@linkplain WorldHost#busy busy} keeps
 * the world from idling and hibernating.
 *
 * <p>Threads: the world itself is only touched by its tick thread, or by the server thread while it has no slot.
 * Lifecycle fields are guarded by {@code this}.
 */
public final class ManagedWorld implements ScheduledWorld{
    private static final System.Logger LOG = System.getLogger(ManagedWorld.class.getName());

    private final WorldId id;
    private final WorldConfig config;
    private final LifecyclePolicy policy;
    private final CheckpointStore store;
    private final ServerThreads threads;
    private final WorldScheduler scheduler;
    private final WorldHost host;
    private final MatchListener matches;
    /** What the host said about the world (its map and mode), in every checkpoint's labels. */
    private final SortedMap<String, String> hostLabels;

    // Guarded by this; the world is also read by its tick thread.
    private Lifecycle lifecycle;
    private volatile World world;
    private WorldScheduler.Slot slot;
    private CompletableFuture<Void> released = CompletableFuture.completedFuture(null);
    private CompletableFuture<Void> saveFlight = CompletableFuture.completedFuture(null);
    // One publication at a time per world, so the store's check against older captures is atomic. Captures may
    // finish encoding out of order; the store refuses the late, older ones.
    private final Object publishing = new Object();
    private CompletableFuture<Void> waking, quiescing;
    private Durable durable;
    private String error, recovery;
    private Path bundle;
    /** The match the world plays, from {@link #open} or the restored generation's label. */
    private volatile String match;
    /** Set by {@link #end()}: the final generation says its match is over (label {@code ended}), so nothing resumes it. */
    private volatile boolean ended;

    // Tick thread only.
    private final ArrayList<CrashBundle.Mark> trail = new ArrayList<>();
    private long emptySince = -1, lastSave, retryAt, lastMatch = -1;
    private volatile boolean saving;

    // Published by the tick thread for status and the server thread.
    private volatile long tick, trimTo = -1;
    private volatile int members;
    private final AtomicInteger restores = new AtomicInteger();

    /** A published generation. */
    record Durable(long generation, long tick, long logPosition, Instant published){
    }

    /** How captures become bytes on {@code io-cpu}. A seam, like {@link FileOps}, so tests can hold an encoding back. */
    interface Encoder{
        byte[] encode(Capture capture, Compression compression, SortedMap<String, String> labels);
    }

    private volatile Encoder encoder = Capture::encode;
    /** Every how many ticks this world writes a match checkpoint, asked at each tick: it follows the world's live config. */
    private volatile java.util.function.ToIntFunction<WorldId> matchEvery;

    ManagedWorld(WorldId id, WorldConfig config, LifecyclePolicy policy, CheckpointStore store, ServerThreads threads, WorldScheduler scheduler,
                 WorldHost host){
        this(id, config, policy, store, threads, scheduler, host, MatchListener.NONE, Collections.emptySortedMap());
    }

    ManagedWorld(WorldId id, WorldConfig config, LifecyclePolicy policy, CheckpointStore store, ServerThreads threads, WorldScheduler scheduler,
                 WorldHost host, MatchListener matches, SortedMap<String, String> hostLabels){
        this.id = id;
        this.config = config;
        this.policy = policy;
        this.store = store;
        this.threads = threads;
        this.scheduler = scheduler;
        this.host = Objects.requireNonNull(host, "host");
        this.matches = Objects.requireNonNull(matches, "matches");
        this.hostLabels = Collections.unmodifiableSortedMap(new TreeMap<>(hostLabels));
        this.matchEvery = ignored -> policy.matchCheckpointEvery();
    }

    /** Replaces where the match checkpoint interval comes from ({@link LifecyclePolicy#matchCheckpointEvery} by default). */
    void matchEvery(java.util.function.ToIntFunction<WorldId> matchEvery){
        this.matchEvery = Objects.requireNonNull(matchEvery, "matchEvery");
    }

    public WorldId id(){
        return id;
    }

    public WorldConfig config(){
        return config;
    }

    /** The match the world plays, or null before it opened. */
    public String match(){
        return match;
    }

    /** A new match's id: the world and when it started, which sorts by time ({@code survival-20261029T171500Z}). */
    static String newMatch(WorldId id, Instant now){
        return id.name() + "-" + java.time.format.DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(now);
    }

    public synchronized Lifecycle lifecycle(){
        return lifecycle;
    }

    /** The scheduler slot while resident, else null. */
    public synchronized WorldScheduler.Slot slot(){
        return slot;
    }

    // ---- Opening ----

    /** Publishes {@code fresh}'s first generation, then schedules it. Runs on the server thread. */
    CompletableFuture<ManagedWorld> open(World fresh){
        return open(fresh, newMatch(id, Instant.now()), false);
    }

    /**
     * Publishes {@code world}'s first generation here, then schedules it: a fresh world starts match {@code match}, a
     * world {@code resumed} from disk goes on with it. Runs on the server thread.
     */
    CompletableFuture<ManagedWorld> open(World world, String match, boolean resumed){
        World fresh = world;
        long now = scheduler.clock().nanoTime();
        this.match = Objects.requireNonNull(match, "match");
        if(resumed){
            try{
                store.dropMatchAfter(id, fresh.ticks());
            }catch(java.io.IOException e){
                return CompletableFuture.failedFuture(e);
            }
        }
        Capture capture = Checkpoint.capture(fresh);
        return CompletableFuture.supplyAsync(() -> encode(capture), threads.ioCpu())
            .thenApplyAsync(bytes -> {
                CheckpointStore.Generation generation = publish(bytes);
                if(matchDue(capture.tick())) publishMatch(capture.tick(), bytes);
                return generation;
            }, threads.io())
            .thenApplyAsync(generation -> {
                synchronized(this){
                    durable = new Durable(generation.number(), capture.tick(), capture.logPosition(), Instant.now());
                    attach(fresh, now, !resumed);
                    lifecycle = Lifecycle.RUNNING;
                    schedule(WorldScheduler.Mode.ACTIVE);
                }
                return this;
            }, threads.server());
    }

    /** Replaces how captures are encoded, for the saves that start after this call. */
    void encoder(Encoder encoder){
        this.encoder = Objects.requireNonNull(encoder, "encoder");
    }

    /** A world that is on disk only. */
    synchronized void hibernated(){
        lifecycle = Lifecycle.HIBERNATED;
    }

    private void attach(World attached, long now, boolean fresh){
        world = attached;
        tick = attached.ticks();
        members = attached.members().size();
        attached.inbox().signal(this::inputArrived);
        trimTo = -1;
        lastSave = now;
        emptySince = -1;
        retryAt = 0;
        saving = false;
        lastMatch = attached.ticks();
        trail.clear();
        matches.resident(id, match, attached.ticks(), fresh, hostLabels, attached);
        host.attached(attached);
    }

    /** Adds a slot once the previous one is gone, so two turns never run at once. Holds this. */
    private void schedule(WorldScheduler.Mode mode){
        World scheduled = world;
        released.thenRunAsync(() -> {
            synchronized(this){
                if(world != scheduled || slot != null || lifecycle != Lifecycle.RUNNING) return;
                slot = scheduler.add(id.name(), id.hash(), this, mode);
            }
        }, threads.server());
    }

    // ---- Inputs ----

    /**
     * Offers an input from any thread. A quiescing world cancels its hibernation; a hibernated one wakes first, and
     * the future completes once the input is in its inbox. False if the inbox refused it or the world failed.
     */
    public CompletableFuture<Boolean> offer(Input input){
        synchronized(this){
            switch(lifecycle){
                case RUNNING -> {
                    return CompletableFuture.completedFuture(world.inbox().offer(input));
                }
                case QUIESCING -> {
                    cancelQuiesce();
                    return CompletableFuture.completedFuture(world.inbox().offer(input));
                }
                case FAILED -> {
                    return CompletableFuture.completedFuture(false);
                }
                default -> {
                }
            }
        }
        return wake().thenCompose(ignored -> offer(input));
    }

    /** The inbox's signal: an input reached an empty inbox. Runs on the offering thread; must not block. */
    private void inputArrived(){
        WorldScheduler.Slot current;
        synchronized(this){
            current = slot;
        }
        if(current != null && current.mode() == WorldScheduler.Mode.MAINTENANCE) current.mode(WorldScheduler.Mode.ACTIVE);
    }

    /**
     * Input waits in front of the inbox (a session mailbox): a world in maintenance goes back to 60 Hz, a quiescing
     * one cancels its hibernation and a hibernated one wakes. Any thread; it doesn't block.
     */
    public void inputWaiting(){
        boolean wake;
        synchronized(this){
            switch(lifecycle){
                case RUNNING -> {
                    if(slot != null && slot.mode() == WorldScheduler.Mode.MAINTENANCE) slot.mode(WorldScheduler.Mode.ACTIVE);
                    return;
                }
                case QUIESCING -> {
                    cancelQuiesce();
                    return;
                }
                case HIBERNATED -> wake = true;
                default -> {
                    return;
                }
            }
        }
        if(wake) wake().exceptionally(failure -> null);
    }

    // ---- The tick thread ----

    @Override
    public void tick(TickProbe probe){
        World current = world;
        host.beforeTick(current);
        current.tick(probe);
        tick = current.ticks();
        if(current.ticks() % policy.hashEvery() == 0) trail.add(new CrashBundle.Mark(current.ticks(), current.hash()));
        host.afterTick(current);
        // per tick, not per turn: a catch-up turn runs up to four ticks, and the aligned one may not be its last
        if(matchDue(current.ticks()) && current.ticks() != lastMatch){
            // before the checkpoint, so a recorder that starts here has the world as that checkpoint holds it
            matches.keyframe(id, match, current.ticks(), hostLabels, current);
            matchCheckpoint(current);
        }
    }

    @Override
    public void maintain(){
        host.maintain(world);
    }

    @Override
    public void boundary(){
        World current = world;
        long trim = trimTo;
        if(trim > current.log().start()){
            current.log().trim(trim);
            trail.removeIf(mark -> mark.tick() < durableTick());
        }
        members = current.members().size();
        long now = scheduler.clock().nanoTime();
        boolean empty = members == 0;
        if(!empty) emptySince = -1;
        else if(emptySince < 0) emptySince = now;
        WorldScheduler.Slot own = slot();
        if(own == null) return;
        WorldScheduler.Mode mode = own.mode();
        boolean idle = empty && !policy.continuous() && current.inbox().size() == 0 && !host.busy();
        if(mode == WorldScheduler.Mode.ACTIVE && idle && now - emptySince >= policy.idleAfter().toNanos()){
            own.mode(WorldScheduler.Mode.MAINTENANCE);
        }else if(mode == WorldScheduler.Mode.MAINTENANCE && !idle){
            own.mode(WorldScheduler.Mode.ACTIVE);
        }
        if(!saving && now - lastSave >= policy.autosaveEvery().toNanos() && current.ticks() != durableTick()) autosave(current, now);
        if(mode == WorldScheduler.Mode.MAINTENANCE && idle && now - emptySince >= policy.hibernateAfter().toNanos() && now >= retryAt){
            retryAt = now + policy.retryAfter().toNanos();
            quiesce(false);
        }
    }

    /** A fork now, encoding and publication off the tick thread. */
    private void autosave(World current, long now){
        saving = true;
        lastSave = now;
        World fork = fork(current);
        var flight = new CompletableFuture<Void>();
        synchronized(this){
            saveFlight = flight;
        }
        try{
            encodeAndPublish(fork, flight);
        }catch(RejectedExecutionException e){
            synchronized(this){
                error = "autosave skipped: io-cpu is full";
            }
            saving = false;
            flight.complete(null);
        }
    }

    private void encodeAndPublish(World fork, CompletableFuture<Void> flight){
        CompletableFuture.supplyAsync(() -> {
            Capture capture = Checkpoint.capture(fork);
            return new Encoded(capture, encode(capture));
        }, threads.ioCpu()).thenApplyAsync(encoded -> {
            CheckpointStore.Generation generation = publish(encoded.bytes());
            return new Durable(generation.number(), encoded.capture().tick(), encoded.capture().logPosition(), Instant.now());
        }, threads.io()).whenComplete((published, failure) -> {
            synchronized(this){
                if(failure != null){
                    error = "autosave failed: " + cause(failure);
                    LOG.log(System.Logger.Level.WARNING, id + ": " + error);
                }else{
                    advance(published);
                }
            }
            saving = false;
            flight.complete(null);
        });
    }

    private record Encoded(Capture capture, byte[] bytes){
    }

    /** A fork to capture off the tick thread, holding the live inbox's waiting inputs as they are now. */
    private static World fork(World current){
        World fork = current.fork();
        fork.inbox().restore(current.inbox().snapshot());
        return fork;
    }

    private boolean matchDue(long tick){
        int every = matchEvery.applyAsInt(id);
        return every > 0 && tick % every == 0;
    }

    /** A fork at an aligned tick; encoding and writing off the tick thread, like an autosave. */
    private void matchCheckpoint(World current){
        long at = current.ticks();
        lastMatch = at;
        World fork = fork(current);
        try{
            CompletableFuture.supplyAsync(() -> encode(Checkpoint.capture(fork)), threads.ioCpu())
                .thenAcceptAsync(bytes -> publishMatch(at, bytes), threads.io())
                .whenComplete((written, failure) -> {
                    if(failure != null) matchFailed(at, cause(failure));
                });
        }catch(RejectedExecutionException e){
            matchFailed(at, "io-cpu is full");
        }
    }

    /** Writes a match checkpoint; a failure is reported, and costs the match only that checkpoint. */
    private void publishMatch(long at, byte[] bytes){
        Path file;
        try{
            file = store.publishMatch(id, bytes);
        }catch(java.io.IOException | RuntimeException e){
            matchFailed(at, cause(e));
            return;
        }
        String label = Checkpoint.header(bytes).label("match");
        try{
            matches.checkpoint(id, label != null ? label : match, at, file);
        }catch(RuntimeException e){
            LOG.log(System.Logger.Level.WARNING, id + ": the match listener failed on the checkpoint at tick " + at, e);
        }
    }

    private synchronized void matchFailed(long at, String why){
        error = "the match checkpoint at tick " + at + " was not written: " + why;
        LOG.log(System.Logger.Level.WARNING, id + ": " + error);
    }

    @Override
    public void failed(Throwable failure){
        World dead;
        long failedTick;
        List<CrashBundle.Mark> marks = List.copyOf(trail);
        Durable last;
        synchronized(this){
            dead = world;
            failedTick = dead.ticks();
            last = durable;
            lifecycle = Lifecycle.FAILED;
            error = "tick " + failedTick + " threw " + failure;
            if(slot != null){
                released = slot.remove();
                slot = null;
            }
            if(quiescing != null){
                quiescing.completeExceptionally(failure);
                quiescing = null;
            }
            world = null;
        }
        LOG.log(System.Logger.Level.ERROR, id + " failed at tick " + failedTick, failure);
        host.failed(failure);
        if(last == null) return;
        CompletableFuture.supplyAsync(() -> {
            try{
                byte[] checkpoint = Files.readAllBytes(store.dir(id).resolve(String.format("gen-%012d.ssnp", last.generation())));
                CrashBundle crash = CrashBundle.of(checkpoint, dead, marks, failedTick, failure, labels());
                return store.writeBundle(id, failedTick, crash.encode());
            }catch(java.io.IOException e){
                throw new CompletionException(e);
            }
        }, threads.io()).whenComplete((path, problem) -> {
            synchronized(this){
                if(problem != null) error += "; the crash bundle failed: " + cause(problem);
                else bundle = path;
            }
        });
    }

    // ---- Hibernation ----

    /**
     * Quiesces for good, members or not, because the match is over (a reload onto the next map, a stop): the final
     * generation carries the label {@code ended}, which {@link WorldManager#prepare} never resumes. Any thread.
     */
    public CompletableFuture<Void> end(){
        ended = true;
        return quiesce(true);
    }

    /**
     * Quiesces: seals the inbox, removes the slot, and publishes a final generation on the server thread; then the
     * world leaves memory. {@code force} hibernates even if members are present (shutdown). Any thread.
     */
    public CompletableFuture<Void> quiesce(boolean force){
        synchronized(this){
            if(lifecycle == Lifecycle.QUIESCING) return quiescing;
            if(lifecycle != Lifecycle.RUNNING) return CompletableFuture.completedFuture(null);
            lifecycle = Lifecycle.QUIESCING;
            quiescing = new CompletableFuture<>();
            world.inbox().seal();
            if(slot != null){
                released = slot.remove();
                slot = null;
            }
            CompletableFuture<Void> done = quiescing;
            // After the last turn, and after an autosave in flight, so the final generation is published last.
            CompletableFuture.allOf(released, saveFlight.handle((ignored, failure) -> null))
                .thenRunAsync(() -> finalSave(force, done), threads.server());
            return done;
        }
    }

    /** On the server thread, with no slot: nothing else touches the world. */
    private void finalSave(boolean force, CompletableFuture<Void> done){
        World frozen;
        synchronized(this){
            if(lifecycle != Lifecycle.QUIESCING || quiescing != done) return;
            frozen = world;
            if(!force && (frozen.inbox().size() > 0 || frozen.members().size() > 0 || host.busy())){
                cancelQuiesce();
                return;
            }
        }
        Capture capture;
        try{
            capture = Checkpoint.capture(frozen);
        }catch(RuntimeException e){
            hibernationFailed(done, e);
            return;
        }
        CompletableFuture.supplyAsync(() -> encode(capture), threads.ioCpu())
            .thenApplyAsync(this::publish, threads.io())
            .whenCompleteAsync((generation, failure) -> {
                synchronized(this){
                    if(failure == null){
                        advance(new Durable(generation.number(), capture.tick(), capture.logPosition(), Instant.now()));
                        if(generation.header().tick() != capture.tick()) failure = new IllegalStateException("the final generation is not at the world's tick");
                    }
                    if(lifecycle != Lifecycle.QUIESCING || quiescing != done){
                        // A join cancelled it: the generation is just another durable one, if the store took it.
                        if(failure != null){
                            error = "the cancelled final save at tick " + capture.tick() + " did not publish: " + cause(failure);
                            LOG.log(System.Logger.Level.WARNING, id + ": " + error);
                        }
                        done.complete(null);
                        return;
                    }
                    if(failure != null){
                        hibernationFailed(done, failure);
                        return;
                    }
                    world = null;
                    trimTo = -1;
                    lifecycle = Lifecycle.HIBERNATED;
                    try{
                        matches.hibernated(id, match, capture.tick());
                    }catch(RuntimeException e){
                        LOG.log(System.Logger.Level.WARNING, id + ": the match listener failed on hibernation", e);
                    }
                    done.complete(null);
                }
            }, threads.server());
    }

    private synchronized void hibernationFailed(CompletableFuture<Void> done, Throwable failure){
        error = "hibernation failed, staying resident on generation " + (durable == null ? 0 : durable.generation()) + ": " + cause(failure);
        LOG.log(System.Logger.Level.WARNING, id + ": " + error);
        resume(WorldScheduler.Mode.MAINTENANCE);
        done.completeExceptionally(failure);
    }

    /** A join or input arrived while quiescing. Holds this. */
    private void cancelQuiesce(){
        resume(WorldScheduler.Mode.ACTIVE);
        quiescing.complete(null);
        quiescing = null;
    }

    /** Back to running from quiescing. Holds this. */
    private void resume(WorldScheduler.Mode mode){
        world.inbox().unseal();
        lifecycle = Lifecycle.RUNNING;
        schedule(mode);
    }

    // ---- Waking ----

    /** Restores the current generation and schedules the world; every concurrent caller gets the same future. */
    public CompletableFuture<Void> wake(){
        CompletableFuture<Void> flight;
        synchronized(this){
            switch(lifecycle){
                case RUNNING, QUIESCING -> {
                    if(lifecycle == Lifecycle.QUIESCING) cancelQuiesce();
                    return CompletableFuture.completedFuture(null);
                }
                case WAKING -> {
                    return waking;
                }
                case FAILED -> {
                    return CompletableFuture.failedFuture(new IllegalStateException(id + " failed: " + error + "; recover() it"));
                }
                default -> {
                }
            }
            lifecycle = Lifecycle.WAKING;
            flight = waking = new CompletableFuture<>();
        }
        restore(flight, Lifecycle.HIBERNATED);
        return flight;
    }

    /** Restores a failed world from its last durable generation. */
    public CompletableFuture<Void> recover(){
        CompletableFuture<Void> flight;
        synchronized(this){
            if(lifecycle == Lifecycle.WAKING) return waking;
            if(lifecycle != Lifecycle.FAILED) return CompletableFuture.completedFuture(null);
            lifecycle = Lifecycle.WAKING;
            flight = waking = new CompletableFuture<>();
        }
        restore(flight, Lifecycle.FAILED);
        return flight;
    }

    private void restore(CompletableFuture<Void> flight, Lifecycle onFailure){
        restores.incrementAndGet();
        CompletableFuture.supplyAsync(() -> {
            try{
                CheckpointStore.Loaded loaded = store.load(id).orElseThrow(() -> new IllegalStateException(id + " has no checkpoint"));
                store.dropMatchAfter(id, loaded.generation().header().tick());
                return loaded;
            }catch(java.io.IOException e){
                throw new CompletionException(e);
            }
        }, threads.io()).thenApplyAsync(loaded -> new Restored(loaded, Checkpoint.decode(loaded.bytes()).restore(config)), threads.ioCpu())
            .whenCompleteAsync((restored, failure) -> {
                synchronized(this){
                    if(failure != null){
                        lifecycle = onFailure;
                        error = "wake failed: " + cause(failure);
                        waking = null;
                        flight.completeExceptionally(failure);
                        return;
                    }
                    CheckpointStore.Generation generation = restored.loaded().generation();
                    recovery = restored.loaded().recovery();
                    String label = generation.header().label("match");
                    if(label != null) match = label;
                    World woken = restored.world();
                    durable = new Durable(generation.number(), woken.ticks(), woken.log().position(),
                        Instant.ofEpochMilli(generation.file().toFile().lastModified()));
                    attach(woken, scheduler.clock().nanoTime(), false);
                    lifecycle = Lifecycle.RUNNING;
                    waking = null;
                    schedule(WorldScheduler.Mode.ACTIVE);
                    flight.complete(null);
                }
            }, threads.server());
    }

    private record Restored(CheckpointStore.Loaded loaded, World world){
    }

    // ---- Shared ----

    /** Makes {@code published} the durable generation if it is newer: saves can complete out of order. Holds this. */
    private void advance(Durable published){
        if(durable != null && published.generation() <= durable.generation()) return;
        durable = published;
        trimTo = published.logPosition();
    }

    private CheckpointStore.Generation publish(byte[] bytes){
        synchronized(publishing){
            try{
                return store.publish(id, bytes);
            }catch(java.io.IOException e){
                throw new CompletionException(e);
            }
        }
    }

    private byte[] encode(Capture capture){
        return encoder.encode(capture, policy.compression(), labels());
    }

    private SortedMap<String, String> labels(){
        var labels = new TreeMap<String, String>(hostLabels);
        labels.put("world", id.name());
        labels.put("time", Instant.now().toString());
        String current = match;
        if(current != null) labels.put("match", current);
        if(ended) labels.put("ended", "true");
        return labels;
    }

    private synchronized long durableTick(){
        return durable == null ? -1 : durable.tick();
    }

    private static String cause(Throwable failure){
        Throwable at = failure;
        while((at instanceof CompletionException || at instanceof ExecutionException) && at.getCause() != null) at = at.getCause();
        return at.toString();
    }

    public synchronized WorldStatus status(){
        boolean resident = world != null;
        return new WorldStatus(id, lifecycle, slot == null ? WorldScheduler.Mode.PARKED : slot.mode(), resident ? tick : durableTick(),
            resident ? members : 0, durable == null ? 0 : durable.generation(), durableTick(),
            durable == null ? null : Duration.between(durable.published(), Instant.now()), slot == null ? "" : slot.stats().summary(),
            error, bundle, recovery);
    }

    /** How many times this world was restored from disk: one per wake, however many callers. */
    public int restores(){
        return restores.get();
    }

    @Override
    public String toString(){
        return "ManagedWorld[" + id + ", " + lifecycle() + "]";
    }
}
