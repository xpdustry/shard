package com.xpdustry.shard.server.sched;

import com.xpdustry.shard.server.Hang;
import com.xpdustry.shard.server.world.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.lang.management.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link WorldScheduler}: fairness under overload, bounded catch-up, constant Δ, modes, the watchdog, accounting. In
 * virtual time ({@link ManualClock}): a tick's work sleeps on the clock, and {@link #pass} moves it from one event
 * to the next once the scheduler has done everything due, so a busy machine changes nothing the tests check.
 */
@Timeout(value = 5, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class WorldSchedulerTest{
    static final long PERIOD = WorldScheduler.PERIOD, MS = 1_000_000L;

    final ManualClock clock = new ManualClock();

    /** A world whose ticks take a set virtual time; counts what the scheduler asks of it. */
    class Fake implements ScheduledWorld{
        final AtomicLong ticks = new AtomicLong(), maintained = new AtomicLong(), boundaries = new AtomicLong();
        final AtomicInteger concurrent = new AtomicInteger(), maxConcurrent = new AtomicInteger();
        volatile long workNanos;
        volatile Throwable failure;
        final CountDownLatch failed = new CountDownLatch(1);

        Fake(long workNanos){
            this.workNanos = workNanos;
        }

        @Override
        public void tick(TickProbe probe){
            maxConcurrent.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
            probe.enter(Phase.BUILDINGS);
            work(workNanos);
            probe.exit();
            ticks.incrementAndGet();
            concurrent.decrementAndGet();
        }

        void work(long nanos){
            if(nanos > 0) clock.sleepNanos(nanos);
        }

        @Override
        public void maintain(){
            maintained.incrementAndGet();
        }

        @Override
        public void boundary(){
            boundaries.incrementAndGet();
        }

        @Override
        public void failed(Throwable error){
            failure = error;
            failed.countDown();
        }
    }

    WorldScheduler scheduler(int threads){
        return scheduler(WorldScheduler.Settings.of(threads), stall -> {});
    }

    WorldScheduler scheduler(WorldScheduler.Settings settings, java.util.function.Consumer<WorldScheduler.Stall> stalls){
        return new WorldScheduler(settings, stalls, clock);
    }

    /** Waits, in real time, until nothing happens before the clock moves and the watchdog has seen the time. */
    void settle(WorldScheduler scheduler){
        long deadline = System.nanoTime() + Hang.NANOS;
        while(!scheduler.idle(clock) || !clock.observed()){
            if(System.nanoTime() > deadline) fail("the scheduler never settled at " + clock + ": " + scheduler.slots());
            Thread.onSpinWait();
        }
    }

    /** Lets {@code nanos} of virtual time pass, event by event, at most a period at a time. */
    void pass(WorldScheduler scheduler, long nanos){
        long end = clock.nanoTime() + nanos;
        while(true){
            settle(scheduler);
            long now = clock.nanoTime();
            if(now >= end) return;
            long next = Math.min(Math.min(clock.nextWake(), scheduler.nextDeadline()), Math.min(end, now + PERIOD));
            clock.advance(next - now);
        }
    }

    /** Removes {@code slot}, letting virtual time pass until its running turn ends. */
    void remove(WorldScheduler scheduler, WorldScheduler.Slot slot){
        CompletableFuture<Void> gone = slot.remove();
        while(!gone.isDone()) pass(scheduler, PERIOD);
        gone.join();
    }

    @Test
    void aHeavyWorldDoesNotStarveLightOnes(){
        try(var scheduler = scheduler(2)){
            var heavy = new Fake(5 * PERIOD);
            scheduler.add("heavy", 0, heavy, WorldScheduler.Mode.ACTIVE);
            var light = new ArrayList<WorldScheduler.Slot>();
            var fakes = new ArrayList<Fake>();
            for(int i = 1; i <= 8; i++){
                var fake = new Fake(200_000);
                fakes.add(fake);
                light.add(scheduler.add("light-" + i, i, fake, WorldScheduler.Mode.ACTIVE));
            }
            WorldScheduler.Slot heavySlot = scheduler.slots().getFirst();
            pass(scheduler, 2000 * MS);
            for(int i = 0; i < light.size(); i++){
                long ticks = fakes.get(i).ticks.get();
                assertTrue(ticks >= 118 && ticks <= 120, "light world " + i + " ran " + ticks + " ticks in 2 s, want 120: " + light.get(i));
                assertEquals(0, light.get(i).stats().dropped(), light.get(i).toString());
            }
            assertTrue(heavy.ticks.get() <= 25, "the heavy world can't keep up: " + heavySlot);
            assertTrue(heavySlot.stats().dropped() > 0, "its lag is reported: " + heavySlot);
            assertEquals(1, heavySlot.stats().maxTurnTicks(), "a world that is behind never catches up in bursts");
        }
    }

    @Test
    void catchUpIsBoundedPerTurn(){
        try(var scheduler = scheduler(1)){
            // A short stall (2.4 periods) is caught up within one turn, nothing dropped.
            var hiccup = new Fake(0){
                int n;

                @Override
                public void tick(TickProbe probe){
                    if(++n == 30) work((long)(2.4 * PERIOD));
                    super.tick(probe);
                }
            };
            WorldScheduler.Slot short_ = scheduler.add("short", 1, hiccup, WorldScheduler.Mode.ACTIVE);
            pass(scheduler, 1200 * MS);
            assertEquals(0, short_.stats().dropped(), short_.toString());
            assertTrue(short_.stats().maxTurnTicks() >= 2 && short_.stats().maxTurnTicks() <= 1 + WorldScheduler.MAX_CATCH_UP, short_.toString());
            remove(scheduler, short_);

            // A long one (8 periods) is dropped, not replayed: the turn stops at the bound.
            var stall = new Fake(0){
                int n;

                @Override
                public void tick(TickProbe probe){
                    if(++n == 30) work(8 * PERIOD);
                    super.tick(probe);
                }
            };
            WorldScheduler.Slot long_ = scheduler.add("long", 2, stall, WorldScheduler.Mode.ACTIVE);
            pass(scheduler, 1200 * MS);
            assertTrue(long_.stats().maxTurnTicks() <= 1 + WorldScheduler.MAX_CATCH_UP, long_.toString());
            assertTrue(long_.stats().dropped() >= 4, long_.toString());
        }
    }

    @Test
    void deltaNeverChangesUnderOverload(){
        World world = new World(TestWorlds.config(1));
        var crashed = new AtomicReference<Throwable>();
        var ran = new AtomicLong();
        var stalled = new ScheduledWorld(){
            @Override
            public void tick(TickProbe probe){
                long before = world.ticks();
                world.tick(probe);
                assertEquals(before + 1, world.ticks());
                if(ran.incrementAndGet() % 10 == 0) clock.sleepNanos(6 * PERIOD);
            }

            @Override
            public void failed(Throwable error){
                crashed.set(error);
            }
        };
        try(var scheduler = scheduler(1)){
            WorldScheduler.Slot slot = scheduler.add("slow", 3, stalled, WorldScheduler.Mode.ACTIVE);
            pass(scheduler, 1500 * MS);
            remove(scheduler, slot);
            assertNull(crashed.get());
            assertTrue(slot.stats().dropped() > 0, slot.toString());
            assertEquals(ran.get(), world.ticks(), "every tick is one tick");
            assertEquals(world.ticks(), slot.stats().ticks());
            assertEquals((float)world.ticks(), world.timers().time(), "simulation time is the tick count: Δ = 1");
        }
    }

    @Test
    void theLeaderHandsOffSoSimultaneousDeadlinesStartTogether(){
        try(var scheduler = scheduler(4)){
            var slots = new ArrayList<WorldScheduler.Slot>();
            var fakes = new ArrayList<Fake>();
            for(int i = 0; i < 4; i++){
                var fake = new Fake(8 * MS);
                fakes.add(fake);
                // Ids with the same phase: all deadlines fall together.
                slots.add(scheduler.add("w" + i, 7, fake, WorldScheduler.Mode.ACTIVE));
            }
            // without the hand-off, one worker takes a world and the others sleep on: the scheduler never settles
            pass(scheduler, 1000 * MS);
            for(WorldScheduler.Slot slot : slots){
                assertEquals(0, slot.stats().lateness().quantile(1.0), "worlds due together start together: " + slot);
                assertTrue(slot.stats().ticks() >= 59, slot.toString());
            }
            assertTrue(fakes.stream().allMatch(fake -> fake.maxConcurrent.get() == 1));
        }
    }

    @Test
    void maintenanceTurnsDoNotTickAndModesSwitch(){
        var settings = WorldScheduler.Settings.of(1).withPeriods(PERIOD, 50 * MS);
        try(var scheduler = scheduler(settings, stall -> {})){
            var fake = new Fake(0);
            WorldScheduler.Slot slot = scheduler.add("idle", 4, fake, WorldScheduler.Mode.MAINTENANCE);
            pass(scheduler, 500 * MS);
            assertEquals(0, fake.ticks.get(), "an empty world doesn't tick");
            assertEquals(10, fake.maintained.get(), "one turn per 50 ms");
            slot.mode(WorldScheduler.Mode.ACTIVE);
            pass(scheduler, 300 * MS);
            assertTrue(fake.ticks.get() >= 18, "active again: " + fake.ticks.get());
            slot.mode(WorldScheduler.Mode.PARKED);
            pass(scheduler, 50 * MS);
            long parked = fake.ticks.get() + fake.maintained.get();
            pass(scheduler, 300 * MS);
            assertEquals(parked, fake.ticks.get() + fake.maintained.get(), "a parked world gets no turns");
            assertEquals(WorldScheduler.Mode.PARKED, slot.mode());
            assertEquals(1, fake.maxConcurrent.get(), "never two turns at once");
        }
    }

    @Test
    void aThrowingTickParksTheWorldAndReportsIt(){
        try(var scheduler = scheduler(2)){
            var fake = new Fake(0){
                @Override
                public void tick(TickProbe probe){
                    super.tick(probe);
                    if(ticks.get() == 5) throw new IllegalStateException("five");
                }
            };
            WorldScheduler.Slot slot = scheduler.add("boom", 5, fake, WorldScheduler.Mode.ACTIVE);
            pass(scheduler, 200 * MS);
            assertEquals(0, fake.failed.getCount());
            assertEquals("five", fake.failure.getMessage());
            assertEquals(5, fake.ticks.get());
            assertEquals(WorldScheduler.Mode.PARKED, slot.mode());
        }
    }

    @Test
    void theWatchdogDumpsTheStackOfALongTick(){
        var stalls = new LinkedBlockingQueue<WorldScheduler.Stall>();
        try(var scheduler = scheduler(WorldScheduler.Settings.of(1), stalls::add)){
            var fake = new Fake(0){
                @Override
                public void tick(TickProbe probe){
                    super.tick(probe);
                    if(ticks.get() == 3) stuckInHere();
                }

                void stuckInHere(){
                    clock.sleepNanos(300 * MS);
                }
            };
            WorldScheduler.Slot slot = scheduler.add("stuck", 6, fake, WorldScheduler.Mode.ACTIVE);
            pass(scheduler, 400 * MS);
            // settled, so the watchdog has looked at every time the clock passed
            WorldScheduler.Stall stall = stalls.poll();
            assertNotNull(stall, "no stall reported");
            assertEquals("stuck", stall.world());
            assertFalse(stall.boundary());
            assertTrue(stall.elapsedNanos() > 5 * PERIOD);
            assertTrue(Arrays.stream(stall.stack()).anyMatch(element -> element.getMethodName().equals("stuckInHere")), stall.toString());
            assertEquals(1, slot.stats().stalls(), "one report per long tick");
            assertTrue(stalls.isEmpty());
        }
    }

    @Test
    void theWatchdogSeesALongBoundaryAndTheStatsTimeIt(){
        var stalls = new LinkedBlockingQueue<WorldScheduler.Stall>();
        try(var scheduler = scheduler(WorldScheduler.Settings.of(1), stalls::add)){
            // an autosave's fork, after a turn: outside the tick, so outside its timer
            var fake = new Fake(0){
                @Override
                public void boundary(){
                    super.boundary();
                    if(boundaries.get() == 3) forkingHere();
                }

                void forkingHere(){
                    clock.sleepNanos(300 * MS);
                }
            };
            WorldScheduler.Slot slot = scheduler.add("saving", 6, fake, WorldScheduler.Mode.ACTIVE);
            pass(scheduler, 400 * MS);
            // settled, so the watchdog has looked at every time the clock passed
            WorldScheduler.Stall stall = stalls.poll();
            assertNotNull(stall, "no stall reported");
            assertTrue(stall.boundary(), stall.toString());
            assertTrue(stall.elapsedNanos() > 5 * PERIOD);
            assertTrue(Arrays.stream(stall.stack()).anyMatch(element -> element.getMethodName().equals("forkingHere")), stall.toString());
            assertEquals(1, slot.stats().stalls(), "one report per long boundary");
            assertTrue(stalls.isEmpty());
            Histogram boundaries = slot.stats().boundaryTime();
            assertEquals(fake.boundaries.get(), boundaries.count());
            assertTrue(boundaries.max() >= 300_000, "the longest boundary, in us: " + boundaries.max());
            assertTrue(slot.stats().tickTime().max() < 300_000, "the boundary isn't in the tick timer");
        }
    }

    /**
     * The clock doesn't pass a time before the watchdog has acted on it, however late a busy machine runs the watchdog:
     * here it starts 200 ms late and is descheduled for 10 ms between reading the time and checking the slots. Both
     * once let a full check pass a long boundary unseen.
     */
    @Test
    void theClockWaitsForALateWatchdog(){
        var stalls = new LinkedBlockingQueue<WorldScheduler.Stall>();
        var started = new AtomicLong(-1);
        var fake = new Fake(0){
            @Override
            public void boundary(){
                super.boundary();
                if(boundaries.get() != 3) return;
                started.set(clock.nanoTime());
                clock.sleepNanos(300 * MS);
            }
        };
        WorldScheduler.Settings settings = WorldScheduler.Settings.of(1);
        try(var scheduler = new WorldScheduler(settings, stalls::add, lateWatchdog(200, 10))){
            scheduler.add("late", 6, fake, WorldScheduler.Mode.ACTIVE);
            while(started.get() < 0) pass(scheduler, MS);
            pass(scheduler, started.get() + settings.watchdogBudget() - clock.nanoTime());
            assertTrue(stalls.isEmpty(), "reported within the budget: " + stalls);
            pass(scheduler, MS);
            assertNotNull(stalls.poll(), "the clock passed the budget before the watchdog looked");
            pass(scheduler, 300 * MS);
            assertTrue(stalls.isEmpty(), "one report per long boundary: " + stalls);
        }
    }

    /** {@link #clock}, on which the watchdog thread runs late: before its first park, and after each read of the time. */
    NanoClock lateWatchdog(long startMillis, long afterReadMillis){
        return new NanoClock(){
            final AtomicBoolean parked = new AtomicBoolean();

            @Override
            public long nanoTime(){
                long now = clock.nanoTime();
                if(watchdog()) sleep(afterReadMillis);
                return now;
            }

            @Override
            public void awaitNanos(Condition condition, long nanos) throws InterruptedException{
                clock.awaitNanos(condition, nanos);
            }

            @Override
            public void parkNanos(Object blocker, long nanos){
                if(!parked.getAndSet(true)) sleep(startMillis);
                clock.parkNanos(blocker, nanos);
            }

            @Override
            public void sleepNanos(long nanos){
                clock.sleepNanos(nanos);
            }

            @Override
            public void addWatcher(Thread thread){
                clock.addWatcher(thread);
            }

            static boolean watchdog(){
                return Thread.currentThread().getName().endsWith("watchdog");
            }

            static void sleep(long millis){
                try{
                    Thread.sleep(millis);
                }catch(InterruptedException e){
                    Thread.currentThread().interrupt();
                }
            }
        };
    }

    @Test
    void cpuIsAccountedPerPhase(){
        var threads = ManagementFactory.getThreadMXBean();
        try(var scheduler = scheduler(1)){
            // 2 ms of this thread's CPU per tick, however long the machine makes that take
            var fake = new Fake(0){
                @Override
                void work(long nanos){
                    long end = threads.getCurrentThreadCpuTime() + 2 * MS;
                    while(threads.getCurrentThreadCpuTime() < end) Thread.onSpinWait();
                }
            };
            WorldScheduler.Slot slot = scheduler.add("busy", 8, fake, WorldScheduler.Mode.ACTIVE);
            pass(scheduler, 600 * MS);
            remove(scheduler, slot);
            TickStats stats = slot.stats();
            long ticks = stats.ticks();
            assertTrue(ticks >= 35, stats.summary());
            assertTrue(stats.wallNanos(Phase.BUILDINGS) >= ticks * 2 * MS, stats.summary());
            // one tick in each run of CPU_SAMPLE is measured, so the last, partial run may count nothing
            assertTrue(stats.cpuNanos(Phase.BUILDINGS) >= (ticks - TickStats.CPU_SAMPLE) * 1_500_000L, "spinning is CPU: " + stats.profile());
            assertEquals(0, stats.cpuNanos(Phase.UNITS));
            assertTrue(stats.profile().containsKey("BUILDINGS") && stats.profile().containsKey("BOUNDARY"), stats.profile().toString());
            assertEquals(ticks, stats.tickTime().count());
        }
    }

    @Test
    void removalWaitsForTheRunningTurn(){
        try(var scheduler = scheduler(1)){
            var fake = new Fake(30 * MS);
            WorldScheduler.Slot slot = scheduler.add("leaving", 9, fake, WorldScheduler.Mode.ACTIVE);
            pass(scheduler, PERIOD);
            assertTrue(slot.running(), "the first tick sleeps 30 ms");
            CompletableFuture<Void> gone = slot.remove();
            assertFalse(gone.isDone(), "a turn is running");
            // the turn may catch up a tick or two before it ends
            while(!gone.isDone()) pass(scheduler, PERIOD);
            assertTrue(fake.ticks.get() <= 1 + WorldScheduler.MAX_CATCH_UP);
            long ticks = fake.ticks.get();
            pass(scheduler, 100 * MS);
            assertEquals(ticks, fake.ticks.get());
            assertFalse(scheduler.slots().contains(slot));
        }
    }
}
