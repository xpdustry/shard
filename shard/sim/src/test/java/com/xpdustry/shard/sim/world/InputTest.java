package com.xpdustry.shard.sim.world;

import com.xpdustry.shard.sim.toy.*;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** The bounded inbox, admission in arrival order, membership checks and the command log. */
class InputTest{
    /** Appends to the world's {@link Chaos} counter, so the effect is world state. */
    record Count(int thread, int sequence) implements Command.Script{
        @Override
        public Verdict apply(World world, Cause cause){
            world.part(Chaos.class).fired++;
            return Verdict.ADMITTED;
        }
    }

    record Tap(int value) implements Intent{
        @Override
        public Verdict apply(World world, IntentEnvelope envelope){
            return value >= 0 ? Verdict.ADMITTED : Verdict.rejected("negative tap");
        }
    }

    @Test
    void commandsFromManyThreadsAreAdmittedInArrivalOrder() throws Exception{
        World world = Worlds.quiet(1);
        int threads = 8, each = 400;
        var start = new CountDownLatch(1);
        try(var pool = Executors.newFixedThreadPool(threads)){
            var done = new ArrayList<Future<?>>();
            for(int t = 0; t < threads; t++){
                int thread = t;
                done.add(pool.submit(() -> {
                    start.await();
                    for(int s = 0; s < each; s++) assertTrue(world.submit(new Count(thread, s), new Cause.Plugin("test")));
                    return null;
                }));
            }
            start.countDown();
            for(Future<?> future : done) future.get();
        }
        world.tick();
        CommandLog log = world.log();
        assertEquals(threads * each, log.size());
        int[] last = new int[threads];
        Arrays.fill(last, -1);
        for(int i = 0; i < log.size(); i++){
            CommandLog.Entry entry = log.get(i);
            assertEquals(i, entry.arrival(), "admitted in arrival order");
            assertEquals(0, entry.tick());
            assertTrue(entry.verdict().admitted());
            var count = (Count)((Submission)entry.input()).command();
            assertEquals(last[count.thread()] + 1, count.sequence(), "each thread's order is kept");
            last[count.thread()] = count.sequence();
        }
        assertEquals(threads * each, world.part(Chaos.class).fired);
        assertEquals(0, world.inbox().size());
    }

    @Test
    void theInboxIsBounded(){
        World world = new World(WorldConfig.of(ToyContent.INSTANCE, 8, 8, 0).withInboxCapacity(10));
        for(int i = 0; i < 10; i++) assertTrue(world.submit(new Count(0, i), Cause.SERVER));
        assertFalse(world.submit(new Count(0, 10), Cause.SERVER));
        assertEquals(1, world.inbox().refused());
        world.tick();
        assertEquals(10, world.log().size());
        assertTrue(world.submit(new Count(0, 11), Cause.SERVER), "draining makes room");
    }

    @Test
    void intentsAreCheckedForMembershipRouteAndSequence(){
        World world = Worlds.quiet(2);
        var inbox = world.inbox();
        inbox.offer(new IntentEnvelope(7, 1, 0, 100, new Tap(1)));
        inbox.offer(new Submission(new Members.Join(7, 1), Cause.SERVER));
        inbox.offer(new IntentEnvelope(7, 1, 0, 110, new Tap(1)));
        inbox.offer(new IntentEnvelope(7, 1, 0, 120, new Tap(2)));
        inbox.offer(new IntentEnvelope(7, 0, 1, 130, new Tap(3)));
        inbox.offer(new IntentEnvelope(7, 1, 2, 140, new Tap(-1)));
        inbox.offer(new IntentEnvelope(7, 1, 3, 150, new Tap(4)));
        inbox.offer(new Submission(new Members.Join(7, 1), Cause.SERVER));
        inbox.offer(new Submission(new Members.Leave(7), Cause.SERVER));
        inbox.offer(new IntentEnvelope(7, 1, 4, 160, new Tap(5)));
        world.tick();
        var verdicts = world.log().entries().stream().map(CommandLog.Entry::verdict).toList();
        assertEquals(List.of(
            Verdict.rejected("not a member"),
            Verdict.ADMITTED,
            Verdict.ADMITTED,
            Verdict.rejected("replayed sequence"),
            Verdict.rejected("stale route"),
            Verdict.rejected("negative tap"),
            Verdict.ADMITTED,
            Verdict.rejected("route generation not newer"),
            Verdict.ADMITTED,
            Verdict.rejected("not a member")
        ), verdicts);
        assertFalse(world.members().contains(7));
    }

    @Test
    void theLogTravelsWithForksAndCountsAsHistory(){
        World world = Worlds.quiet(3);
        world.submit(new Members.Join(1, 1), Cause.SERVER);
        world.tick();
        World fork = world.fork();
        assertEquals(world.log().entries(), fork.log().entries());
        fork.submit(new Members.Leave(1), Cause.SERVER);
        fork.tick();
        assertEquals(1, world.log().size());
        assertEquals(2, fork.log().size());
        assertTrue(world.members().contains(1));
    }
}
