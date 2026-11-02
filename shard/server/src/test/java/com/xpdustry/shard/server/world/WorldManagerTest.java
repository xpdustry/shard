package com.xpdustry.shard.server.world;

import com.xpdustry.shard.server.Hang;
import com.xpdustry.shard.server.sched.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

import static org.junit.jupiter.api.Assertions.*;

/** {@link WorldManager} and {@link ManagedWorld}: idling, hibernation, waking, autosave and crashes, on fast clocks. */
class WorldManagerTest{
    static final LifecyclePolicy FAST = LifecyclePolicy.DEFAULT.withTimes(Duration.ofMillis(100), Duration.ofMillis(400),
        Duration.ofMillis(300), Duration.ofMillis(200)).withHashEvery(30);

    @TempDir
    Path root;

    ServerThreads threads;
    WorldScheduler scheduler;
    BlockingFiles files;

    /** The real file system, but publications can be made to fail or to wait. */
    static final class BlockingFiles implements FileOps{
        volatile boolean failing;
        volatile CountDownLatch gate;

        @Override
        public void write(Path file, byte[] bytes) throws IOException{
            CountDownLatch wait = gate;
            if(wait != null){
                try{
                    wait.await();
                }catch(InterruptedException e){
                    throw new InterruptedIOException();
                }
            }
            if(failing) throw new IOException("injected: the disk is full");
            NoSync.INSTANCE.write(file, bytes);
        }

        @Override
        public byte[] read(Path file) throws IOException{
            return SYSTEM.read(file);
        }

        @Override
        public void move(Path from, Path to) throws IOException{
            SYSTEM.move(from, to);
        }

        @Override
        public void forceDirectory(Path dir){
        }

        @Override
        public void delete(Path file) throws IOException{
            SYSTEM.delete(file);
        }

        @Override
        public List<String> list(Path dir) throws IOException{
            return SYSTEM.list(dir);
        }

        @Override
        public void createDirectories(Path dir) throws IOException{
            SYSTEM.createDirectories(dir);
        }
    }

    @BeforeEach
    void start(){
        threads = new ServerThreads(new ServerThreads.Sizes(2, 1, 64));
        scheduler = new WorldScheduler(WorldScheduler.Settings.of(2).withPeriods(WorldScheduler.PERIOD, 50_000_000L), stall -> {});
        files = new BlockingFiles();
    }

    @AfterEach
    void stop(){
        scheduler.close();
        threads.close();
    }

    WorldManager manager(LifecyclePolicy policy){
        return new WorldManager(threads, scheduler, new CheckpointStore(root, files, policy.keepGenerations()), policy);
    }

    static void await(String what, BooleanSupplier condition){
        long deadline = System.nanoTime() + Hang.NANOS;
        while(!condition.getAsBoolean()){
            if(System.nanoTime() > deadline) fail("timed out waiting until " + what);
            sleep(5);
        }
    }

    static void sleep(long millis){
        try{
            Thread.sleep(millis);
        }catch(InterruptedException e){
            throw new AssertionError(e);
        }
    }

    static ManagedWorld create(WorldManager manager, String name) throws Exception{
        ManagedWorld world = manager.create(WorldId.of(name), TestWorlds.config(name.hashCode())).get(Hang.SECONDS, TimeUnit.SECONDS);
        for(Submission build : TestWorlds.crates(6)) assertTrue(world.offer(build).get());
        return world;
    }

    @Test
    void anEmptyWorldIdlesWithTimeFrozenThenHibernates() throws Exception{
        WorldManager manager = manager(FAST);
        ManagedWorld world = create(manager, "idle");
        assertEquals(Lifecycle.RUNNING, world.lifecycle());
        assertEquals(1, world.status().generation(), "a new world publishes its first generation before it runs");
        await("it drops to maintenance", () -> world.status().mode() == WorldScheduler.Mode.MAINTENANCE);
        long frozen = world.status().tick();
        long maintained = world.slot().stats().maintenance();
        // three maintenance turns, counted: a world that was still ticking would have moved its tick by then. The
        // hibernation can come first on a loaded machine, which ends the wait too
        await("maintenance turns, or the hibernation", () -> {
            var slot = world.slot();
            return slot == null || slot.stats().maintenance() >= maintained + 3;
        });
        WorldStatus idle = world.status();
        if(idle.lifecycle() == Lifecycle.RUNNING){
            assertEquals(frozen, idle.tick(), "simulation time is frozen while empty");
        }
        await("it hibernates", () -> world.lifecycle() == Lifecycle.HIBERNATED);
        WorldStatus status = world.status();
        assertEquals(frozen, status.durableTick(), "the final generation is at the frozen tick");
        assertEquals(WorldScheduler.Mode.PARKED, status.mode());
        assertNull(world.slot());
        CheckpointStore.Loaded current = manager.store().load(world.id()).orElseThrow();
        assertEquals(status.generation(), current.generation().number());
        assertEquals(frozen, Checkpoint.decode(current.bytes()).restore(world.config()).ticks());
    }

    @Test
    void joinsWakeAHibernatedWorldOnce() throws Exception{
        WorldManager manager = manager(FAST);
        ManagedWorld world = create(manager, "sleepy");
        await("it hibernates", () -> world.lifecycle() == Lifecycle.HIBERNATED);
        long durable = world.status().durableTick();
        var joins = new ArrayList<CompletableFuture<Boolean>>();
        var start = new CountDownLatch(1);
        try(var pool = Executors.newFixedThreadPool(8)){
            for(int i = 0; i < 8; i++){
                long session = 100 + i;
                joins.add(CompletableFuture.supplyAsync(() -> {
                    try{
                        start.await();
                    }catch(InterruptedException e){
                        throw new CompletionException(e);
                    }
                    return manager.offer(world.id(), TestWorlds.join(session)).join();
                }, pool));
            }
            start.countDown();
            for(CompletableFuture<Boolean> join : joins) assertTrue(join.get(Hang.SECONDS, TimeUnit.SECONDS));
        }
        assertEquals(1, world.restores(), "eight joins, one restore");
        assertEquals(Lifecycle.RUNNING, world.lifecycle());
        await("the members are admitted", () -> world.status().members() == 8);
        assertTrue(world.status().tick() > durable, "it continues from its durable tick");
        assertEquals(WorldScheduler.Mode.ACTIVE, world.status().mode());
        sleep(300);
        assertEquals(Lifecycle.RUNNING, world.lifecycle(), "a world with members doesn't idle");
    }

    @Test
    void aJoinWhileQuiescingCancelsTheHibernation() throws Exception{
        WorldManager manager = manager(FAST);
        ManagedWorld world = create(manager, "almost");
        files.gate = new CountDownLatch(1);
        await("it starts quiescing", () -> world.lifecycle() == Lifecycle.QUIESCING);
        assertTrue(world.offer(TestWorlds.join(7)).get(Hang.SECONDS, TimeUnit.SECONDS));
        assertEquals(Lifecycle.RUNNING, world.lifecycle(), "the join cancelled it");
        files.gate.countDown();
        files.gate = null;
        await("the member is admitted", () -> world.status().members() == 1);
        long tick = world.status().tick();
        await("it ticks again", () -> world.status().tick() > tick);
        sleep(600);
        assertEquals(Lifecycle.RUNNING, world.lifecycle());
    }

    @Test
    void aCancelledFinalSaveCantReplaceANewerGeneration() throws Exception{
        // One io-cpu thread holds the final save's encoding back while the other encodes the resumed world's autosaves.
        threads.close();
        threads = new ServerThreads(new ServerThreads.Sizes(2, 2, 64));
        WorldManager manager = manager(FAST);
        ManagedWorld world = create(manager, "overtaken");
        var finalTick = new AtomicLong(-1);
        var finalHeld = new CountDownLatch(1);
        var releaseFinal = new CountDownLatch(1);
        var pausing = new AtomicBoolean();
        var autosavePaused = new CountDownLatch(1);
        var releaseAutosaves = new CountDownLatch(1);
        world.encoder((capture, compression, labels) -> {
            if(world.lifecycle() == Lifecycle.QUIESCING && finalTick.compareAndSet(-1, capture.tick())){
                finalHeld.countDown();
                hold(releaseFinal);
            }else if(pausing.get()){
                autosavePaused.countDown();
                hold(releaseAutosaves);
            }
            return capture.encode(compression, labels);
        });
        try{
            assertTrue(finalHeld.await(Hang.SECONDS, TimeUnit.SECONDS), "the final save starts encoding");
            assertTrue(world.offer(TestWorlds.join(7)).get(Hang.SECONDS, TimeUnit.SECONDS));
            assertEquals(Lifecycle.RUNNING, world.lifecycle(), "the join cancelled the hibernation");
            await("a newer autosave publishes", () -> world.status().durableTick() > finalTick.get());
            // Autosaves run one at a time, so once one is paused every earlier one has published.
            pausing.set(true);
            assertTrue(autosavePaused.await(Hang.SECONDS, TimeUnit.SECONDS), "the next autosave starts");
            WorldStatus newer = world.status();
            assertEquals(newer.durableTick(), manager.store().load(world.id()).orElseThrow().generation().header().tick());

            releaseFinal.countDown();
            await("the final save finishes", () -> world.status().generation() != newer.generation()
                || (world.status().error() != null && world.status().error().contains("final save")));
            CheckpointStore.Loaded current = manager.store().load(world.id()).orElseThrow();
            assertEquals(newer.durableTick(), current.generation().header().tick(), "CURRENT still names the newer tick");
            assertEquals(newer.generation(), current.generation().number());
            WorldStatus status = world.status();
            assertEquals(newer.durableTick(), status.durableTick(), "the world's durable generation doesn't go back either");
            assertEquals(newer.generation(), status.generation());
            assertTrue(status.error().contains("tick " + finalTick.get()) && status.error().contains("tick " + newer.durableTick()),
                "the stale capture is reported: " + status.error());
            assertEquals(Lifecycle.RUNNING, status.lifecycle());
        }finally{
            releaseFinal.countDown();
            releaseAutosaves.countDown();
        }
        await("it autosaves again", () -> world.status().durableTick() > finalTick.get() && world.status().generation() > 2);
    }

    static void hold(CountDownLatch latch){
        try{
            if(!latch.await(Hang.SECONDS, TimeUnit.SECONDS)) throw new AssertionError("held for too long");
        }catch(InterruptedException e){
            throw new AssertionError(e);
        }
    }

    @Test
    void aFailedPublicationKeepsTheWorldResident() throws Exception{
        WorldManager manager = manager(FAST);
        ManagedWorld world = create(manager, "stubborn");
        long good = world.status().generation();
        files.failing = true;
        await("hibernation fails", () -> world.status().error() != null && world.status().error().contains("hibernation failed"));
        WorldStatus status = world.status();
        assertEquals(Lifecycle.RUNNING, status.lifecycle(), "it stays resident");
        assertEquals(good, status.generation(), "on the last good generation");
        assertEquals(good, manager.store().load(world.id()).orElseThrow().generation().number());
        assertTrue(status.error().contains("the disk is full"), status.error());
        files.failing = false;
        await("it hibernates once the disk works", () -> world.lifecycle() == Lifecycle.HIBERNATED);
    }

    @Test
    void autosavesPublishWhileTheWorldRuns() throws Exception{
        WorldManager manager = manager(FAST);
        ManagedWorld world = create(manager, "busy");
        assertTrue(world.offer(TestWorlds.join(1)).get());
        await("two autosaves", () -> world.status().generation() >= 3);
        WorldStatus status = world.status();
        assertEquals(Lifecycle.RUNNING, status.lifecycle());
        assertTrue(status.durableTick() > 0 && status.durableTick() <= status.tick(), status.toString());
        // the age is read off the system clock: a bound on it measures the machine, and the two autosaves above show they run
        assertFalse(status.checkpointAge().isNegative(), status.toString());
        assertTrue(manager.store().generations(world.id()).size() <= FAST.keepGenerations());
    }

    @Test
    void aCrashWritesABundleThatReplaysAndTheWorldRecovers() throws Exception{
        var policy = FAST.withTimes(Duration.ofMinutes(5), Duration.ofMinutes(10), Duration.ofMinutes(1), Duration.ofMinutes(10));
        WorldManager manager = manager(policy);
        ManagedWorld world = create(manager, "doomed");
        assertTrue(world.offer(TestWorlds.join(1)).get());
        await("some ticks", () -> world.status().tick() > 40);
        long at = world.status().tick() + 60;
        assertTrue(world.offer(TestWorlds.admin(new TestWorlds.Arm(at))).get());
        await("it fails", () -> world.lifecycle() == Lifecycle.FAILED);
        await("its bundle is written", () -> world.status().bundle() != null);
        WorldStatus failed = world.status();
        assertTrue(failed.error().contains("fuse blew at tick " + at), failed.error());

        CrashBundle bundle = CrashBundle.decode(Files.readAllBytes(failed.bundle()));
        assertEquals(at, bundle.failedTick());
        assertEquals(0, Checkpoint.header(bundle.checkpoint()).tick(), "the last durable generation is the first one");
        var out = new ArrayList<String>();
        Replay.Result result = Replay.run(bundle, Checkpoint.decode(bundle.checkpoint()).config(TestWorlds.CONTENT), Replay.Options.DEFAULT, out::add);
        assertTrue(result.ok() && result.reproduced(), result + "\n" + out);
        assertTrue(result.hashes() >= at / 30 - 1, result.toString());

        var text = new ByteArrayOutputStream();
        int status = ReplayCommand.run(List.of(failed.bundle().toString(), "--hash-every", "60"), new PrintStream(text, true), System.err);
        assertEquals(0, status, text.toString());
        assertTrue(text.toString().contains("replay reproduced the failure at tick " + at), text.toString());

        world.recover().get(Hang.SECONDS, TimeUnit.SECONDS);
        assertEquals(Lifecycle.RUNNING, world.lifecycle());
        await("it ticks past the old failure", () -> world.status().tick() > at + 10);
    }

    @Test
    void aMatchStartedInTheSameSecondAsTheLastGetsAnIdOfItsOwn(){
        // a game over's reload with no wait starts the next map's match within the second the last one started
        WorldManager manager = manager(FAST);
        WorldId id = WorldId.of("again");
        Instant now = Instant.parse("2026-10-29T21:00:00.250Z");
        assertEquals("again-20261029T210000Z", manager.newMatch(id, now));
        assertEquals("again-20261029T210000Z-2", manager.newMatch(id, now.plusMillis(500)));
        assertEquals("again-20261029T210001Z", manager.newMatch(id, now.plusSeconds(1)));
        assertEquals("other-20261029T210000Z", manager.newMatch(WorldId.of("other"), now), "per world");
    }

    @Test
    void matchCheckpointsFallOnAlignedTicksAndARecoveryDropsTheOnesAfterIt() throws Exception{
        // virtual time, three ticks a turn: the aligned ticks (multiples of 3) fall inside turns, never at their end
        var clock = new ManualClock();
        scheduler.close();
        scheduler = new WorldScheduler(WorldScheduler.Settings.of(2), stall -> {}, clock);
        // no autosave: the only generation is the first, at tick 0
        var policy = FAST.withTimes(Duration.ofMinutes(5), Duration.ofMinutes(10), Duration.ofMinutes(1), Duration.ofMinutes(10))
            .withContinuous(true).withMatchCheckpointEvery(LifecyclePolicy.MATCH_ALIGNMENT);
        WorldManager manager = manager(policy);
        ManagedWorld world = create(manager, "match");
        CheckpointStore store = manager.store();
        until(clock, 1, "a turn to end on tick 1 mod 3", () -> world.status().tick() % 3 == 1);
        until(clock, 3, "tick 1,201", () -> world.status().tick() > 1_200);
        assertEquals(0, world.slot().stats().dropped());
        await("three match checkpoints", () -> matchTicks(store, world.id()).size() >= 3);
        assertEquals(List.of(0L, 600L, 1200L), matchTicks(store, world.id()).subList(0, 3));
        for(long tick : matchTicks(store, world.id())){
            assertEquals(tick, Checkpoint.header(store.loadMatch(world.id(), tick).orElseThrow()).tick());
        }
        assertEquals(List.of("gen-000000000001.ssnp"), store.generations(world.id()));

        long at = world.status().tick() + 100;
        assertTrue(world.offer(TestWorlds.admin(new TestWorlds.Arm(at))).get());
        until(clock, 3, "it fails", () -> world.lifecycle() == Lifecycle.FAILED);
        world.recover().get(Hang.SECONDS, TimeUnit.SECONDS);
        assertEquals(List.of(0L), matchTicks(store, world.id()), "restored to tick 0, the rest belonged to the failed run");
        until(clock, 3, "it writes them again", () -> matchTicks(store, world.id()).contains(600L));
    }

    /**
     * Lets virtual time pass {@code periods} at a time until {@code condition} holds, the scheduler settled before
     * each check: a step of {@code n} periods is a turn of {@code n} ticks (up to the catch-up limit).
     */
    void until(ManualClock clock, int periods, String what, BooleanSupplier condition){
        long deadline = System.nanoTime() + Hang.NANOS;
        while(true){
            // settled: every turn asleep on the clock, and every thread that watches it has read the new time
            while(!scheduler.idle(clock) || !clock.observed()){
                if(System.nanoTime() > deadline) fail("the scheduler never settled at " + clock);
                Thread.onSpinWait();
            }
            if(condition.getAsBoolean()) return;
            if(System.nanoTime() > deadline) fail("timed out waiting until " + what + " at " + clock);
            clock.advance(periods * scheduler.settings().period());
        }
    }

    static List<Long> matchTicks(CheckpointStore store, WorldId id){
        try{
            return store.matchTicks(id);
        }catch(IOException e){
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void shutdownHibernatesEveryWorldAndAttachWakesThem() throws Exception{
        WorldManager manager = manager(FAST.withTimes(Duration.ofMinutes(5), Duration.ofMinutes(10), Duration.ofMinutes(1), Duration.ofMinutes(10)));
        ManagedWorld a = create(manager, "a"), b = create(manager, "b");
        assertTrue(a.offer(TestWorlds.join(1)).get());
        await("ticks", () -> a.status().tick() > 20 && b.status().tick() > 20);
        manager.shutdown().get(Hang.SECONDS, TimeUnit.SECONDS);
        assertEquals(Lifecycle.HIBERNATED, a.lifecycle());
        assertEquals(Lifecycle.HIBERNATED, b.lifecycle());
        long tick = a.status().durableTick();
        assertEquals(1, Checkpoint.decode(manager.store().load(a.id()).orElseThrow().bytes()).restore(a.config()).members().size());

        WorldManager again = manager(FAST);
        ManagedWorld reattached = again.attach(a.id(), a.config());
        assertEquals(Lifecycle.HIBERNATED, reattached.lifecycle());
        reattached.wake().get(Hang.SECONDS, TimeUnit.SECONDS);
        assertEquals(tick, reattached.status().durableTick());
        assertEquals(1, reattached.status().members());
        assertThrows(FileNotFoundException.class, () -> again.attach(WorldId.of("nowhere"), a.config()));
    }

    @Test
    void inputWaitingOutsideTheInboxWakesTheWorldAndKeepsItUp() throws Exception{
        WorldManager manager = manager(FAST);
        var waiting = new ConcurrentLinkedQueue<Submission>();
        var attached = new AtomicInteger();
        var turns = new AtomicLong();
        WorldHost host = new WorldHost(){
            @Override
            public void attached(World world){
                attached.incrementAndGet();
            }

            @Override
            public void beforeTick(World world){
                for(Submission input; (input = waiting.peek()) != null && world.inbox().offer(input); ) waiting.poll();
            }

            @Override
            public void afterTick(World world){
                turns.incrementAndGet();
            }

            @Override
            public boolean busy(){
                return !waiting.isEmpty();
            }
        };
        WorldConfig config = TestWorlds.config(11);
        ManagedWorld world = manager.create(WorldId.of("hosted"), config, () -> new World(config), host).get(Hang.SECONDS, TimeUnit.SECONDS);
        assertEquals(1, attached.get());
        await("it hibernates", () -> world.lifecycle() == Lifecycle.HIBERNATED);

        waiting.add(TestWorlds.join(9));
        world.inputWaiting();
        await("the member is admitted", () -> world.status().members() == 1);
        assertEquals(2, attached.get(), "the wake attached a new world");
        assertTrue(turns.get() > 0);
        assertEquals(Lifecycle.RUNNING, world.lifecycle());
    }

    @Test
    void aBusyHostKeepsAnEmptyWorldAtSixtyHertz() throws Exception{
        WorldManager manager = manager(FAST);
        var busy = new AtomicBoolean(true);
        WorldHost host = new WorldHost(){
            @Override
            public boolean busy(){
                return busy.get();
            }
        };
        WorldConfig config = TestWorlds.config(12);
        ManagedWorld world = manager.create(WorldId.of("busy"), config, () -> new World(config), host).get(Hang.SECONDS, TimeUnit.SECONDS);
        sleep(600);
        assertEquals(Lifecycle.RUNNING, world.lifecycle());
        assertEquals(WorldScheduler.Mode.ACTIVE, world.status().mode());
        busy.set(false);
        await("it hibernates", () -> world.lifecycle() == Lifecycle.HIBERNATED);
    }

    @Test
    void retireHibernatesAWorldWithMembersAndForgetsIt() throws Exception{
        WorldManager manager = manager(FAST);
        ManagedWorld world = create(manager, "retired");
        assertTrue(world.offer(TestWorlds.join(3)).get(Hang.SECONDS, TimeUnit.SECONDS));
        await("the member is admitted", () -> world.status().members() == 1);
        manager.retire(world.id()).get(Hang.SECONDS, TimeUnit.SECONDS);
        assertEquals(Lifecycle.HIBERNATED, world.lifecycle());
        assertTrue(manager.get(world.id()).isEmpty());
        assertEquals(1, Checkpoint.decode(manager.store().load(world.id()).orElseThrow().bytes()).restore(config(world)).members().size());
    }

    static WorldConfig config(ManagedWorld world){
        return world.config();
    }
}
