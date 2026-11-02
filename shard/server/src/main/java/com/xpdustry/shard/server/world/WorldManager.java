package com.xpdustry.shard.server.world;

import com.xpdustry.shard.server.sched.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * The server's worlds, by id (ARCHITECTURE.md §7 "World lifecycle"). It creates worlds, finds hibernated ones on
 * disk, routes inputs (waking a world when one arrives) and reports status. Each world's lifecycle is its
 * {@link ManagedWorld}'s.
 */
public final class WorldManager implements AutoCloseable{
    private final ServerThreads threads;
    private final WorldScheduler scheduler;
    private final CheckpointStore store;
    private final LifecyclePolicy policy;
    private final TreeMap<WorldId, ManagedWorld> worlds = new TreeMap<>();
    /** What {@link #prepare} decided for worlds about to be created: their labels, and whether they resume. */
    private final TreeMap<WorldId, Prepared> prepared = new TreeMap<>();
    /** The matches each world started here: a world reloaded within the second its last match started gets an id of its own. */
    private final TreeMap<WorldId, Set<String>> started = new TreeMap<>();
    private volatile MatchListener matches = MatchListener.NONE;
    private volatile ToIntFunction<WorldId> matchEvery;

    private record Prepared(SortedMap<String, String> labels, boolean resume){
    }

    public WorldManager(ServerThreads threads, WorldScheduler scheduler, CheckpointStore store, LifecyclePolicy policy){
        this.threads = threads;
        this.scheduler = scheduler;
        this.store = store;
        this.policy = policy;
        this.matchEvery = ignored -> policy.matchCheckpointEvery();
    }

    /**
     * Where each world's match checkpoint interval comes from, asked at every tick so it follows a live config: a
     * multiple of {@link LifecyclePolicy#MATCH_ALIGNMENT}, 0 for none. {@link LifecyclePolicy#matchCheckpointEvery} until set.
     */
    public void matchEvery(ToIntFunction<WorldId> source){
        this.matchEvery = Objects.requireNonNull(source, "source");
    }

    public LifecyclePolicy policy(){
        return policy;
    }

    public CheckpointStore store(){
        return store;
    }

    /** Who hears about matches; set it before the first world is created. */
    public void matches(MatchListener listener){
        this.matches = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Before {@link #create(WorldId, WorldConfig, Supplier, WorldHost)} of a world {@code id} for a host: with
     * {@link LifecyclePolicy#resume}, if {@code id}'s current generation carries {@code labels} (the same map and
     * mode) and its match didn't end ({@link #retire}), the world will resume from it. Otherwise its old checkpoints
     * are archived ({@link CheckpointStore#archive}) and the match they held is over.
     *
     * @param labels what the host says about the world; they go in its checkpoints' labels
     * @return where the old checkpoints went, or null if there were none or the world resumes
     */
    public Path prepare(WorldId id, SortedMap<String, String> labels, long millis) throws IOException{
        Header current = policy.resume() ? currentHeader(id) : null;
        boolean resume = current != null && current.label("ended") == null
            && labels.entrySet().stream().allMatch(entry -> entry.getValue().equals(current.label(entry.getKey())));
        synchronized(this){
            prepared.put(id, new Prepared(new TreeMap<>(labels), resume));
        }
        if(resume) return null;
        String ended = current != null ? current.label("match") : matchOnDisk(id);
        Path archived = store.archive(id, millis);
        if(archived != null && ended != null) matches.ended(id, ended);
        return archived;
    }

    /** The header of {@code id}'s current generation, or null when there is none to resume (or it doesn't read). */
    private Header currentHeader(WorldId id){
        try{
            return store.load(id).map(loaded -> loaded.generation().header()).orElse(null);
        }catch(IOException | CheckpointException e){
            return null;
        }
    }

    private String matchOnDisk(WorldId id){
        Header header = currentHeader(id);
        return header == null ? null : header.label("match");
    }

    /** Creates a new world from {@code config}: built off the tick threads, its first generation published, then running. */
    public CompletableFuture<ManagedWorld> create(WorldId id, WorldConfig config){
        return create(id, config, () -> new World(config), WorldHost.NONE);
    }

    /**
     * Creates a new world that {@code fresh} builds on {@code io-cpu} (a map load), run with {@code host}. Its config
     * must be {@code config}, which a wake restores it with. Its first generation is published, then it runs.
     */
    public CompletableFuture<ManagedWorld> create(WorldId id, WorldConfig config, Supplier<World> fresh, WorldHost host){
        Prepared plan;
        synchronized(this){
            plan = prepared.remove(id);
        }
        if(plan == null) plan = new Prepared(Collections.emptySortedMap(), false);
        ManagedWorld managed = register(id, config, host, plan.labels());
        boolean resume = plan.resume();
        return CompletableFuture.supplyAsync(() -> {
                World world = fresh.get();
                // resumed: the host still made its world (it keeps the map's state beside it), but the match goes on
                return resume ? resumed(id, config) : new Opening(world, newMatch(id, Instant.now()), false);
            }, threads.ioCpu())
            .thenComposeAsync(opening -> managed.open(opening.world(), opening.match(), opening.resumed()), threads.server())
            .whenComplete((ignored, failure) -> {
                if(failure != null) unregister(id, managed);
            });
    }

    private record Opening(World world, String match, boolean resumed){
    }

    /**
     * A new match's id for world {@code id}: {@link ManagedWorld#newMatch}, or with {@code -2}, {@code -3}... when a
     * match of the world started here in the same second (a game over's reload with no wait).
     */
    synchronized String newMatch(WorldId id, Instant now){
        String base = ManagedWorld.newMatch(id, now), match = base;
        Set<String> taken = started.computeIfAbsent(id, ignored -> new TreeSet<>());
        for(int n = 2; !taken.add(match); n++) match = base + "-" + n;
        return match;
    }

    /** {@code id}'s current generation, restored: the world and its match. */
    private Opening resumed(WorldId id, WorldConfig config){
        try{
            CheckpointStore.Loaded loaded = store.load(id).orElseThrow(() -> new IllegalStateException(id + " has no checkpoint to resume"));
            World world = Checkpoint.decode(loaded.bytes()).restore(config);
            String match = loaded.generation().header().label("match");
            if(match == null) return new Opening(world, newMatch(id, Instant.now()), true);
            synchronized(this){
                started.computeIfAbsent(id, ignored -> new TreeSet<>()).add(match);
            }
            return new Opening(world, match, true);
        }catch(IOException e){
            throw new CompletionException(e);
        }
    }

    /** Registers a world that is on disk, hibernated; it wakes when an input arrives or {@link ManagedWorld#wake()} is called. */
    public ManagedWorld attach(WorldId id, WorldConfig config) throws IOException{
        return attach(id, config, WorldHost.NONE);
    }

    /** {@link #attach(WorldId, WorldConfig)}, run with {@code host} once it wakes. */
    public ManagedWorld attach(WorldId id, WorldConfig config, WorldHost host) throws IOException{
        if(store.generations(id).isEmpty()) throw new FileNotFoundException(id + " has no checkpoint in " + store.dir(id));
        ManagedWorld managed = register(id, config, host);
        managed.hibernated();
        return managed;
    }

    /**
     * Ends a world's match and hibernates it, members or not ({@link ManagedWorld#end}), then forgets it once its final
     * generation is published (or it failed): its checkpoints stay on disk, but nothing resumes them.
     */
    public CompletableFuture<Void> retire(WorldId id){
        ManagedWorld managed = get(id).orElse(null);
        if(managed == null) return CompletableFuture.completedFuture(null);
        return managed.end().handle((ignored, failure) -> {
            unregister(id, managed);
            String match = managed.match();
            if(match != null) matches.ended(id, match);
            if(failure != null) throw new CompletionException(failure);
            return null;
        });
    }

    private synchronized ManagedWorld register(WorldId id, WorldConfig config, WorldHost host){
        return register(id, config, host, Collections.emptySortedMap());
    }

    private synchronized ManagedWorld register(WorldId id, WorldConfig config, WorldHost host, SortedMap<String, String> labels){
        if(worlds.containsKey(id)) throw new IllegalStateException("world " + id + " already exists");
        var managed = new ManagedWorld(id, config, policy, store, threads, scheduler, host, matches, labels);
        managed.matchEvery(world -> matchEvery.applyAsInt(world));
        worlds.put(id, managed);
        return managed;
    }

    private synchronized void unregister(WorldId id, ManagedWorld managed){
        worlds.remove(id, managed);
    }

    public synchronized Optional<ManagedWorld> get(WorldId id){
        return Optional.ofNullable(worlds.get(id));
    }

    public synchronized List<ManagedWorld> worlds(){
        return List.copyOf(worlds.values());
    }

    /** Routes an input to a world, waking it if needed; false if there is no such world or it refused the input. */
    public CompletableFuture<Boolean> offer(WorldId id, Input input){
        return get(id).map(world -> world.offer(input)).orElse(CompletableFuture.completedFuture(false));
    }

    public List<WorldStatus> status(){
        return worlds().stream().map(ManagedWorld::status).toList();
    }

    /** Hibernates every resident world, members or not: their final generations are published. */
    public CompletableFuture<Void> shutdown(){
        return CompletableFuture.allOf(worlds().stream().map(world -> world.quiesce(true).exceptionally(failure -> null))
            .toArray(CompletableFuture[]::new));
    }

    @Override
    public void close(){
        try{
            shutdown().get(60, TimeUnit.SECONDS);
        }catch(InterruptedException e){
            Thread.currentThread().interrupt();
        }catch(ExecutionException | TimeoutException e){
            throw new IllegalStateException("worlds didn't hibernate on shutdown", e);
        }
    }
}
