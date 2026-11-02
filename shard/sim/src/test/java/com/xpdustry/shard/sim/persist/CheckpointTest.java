package com.xpdustry.shard.sim.persist;

import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.toy.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The checkpoint gate (WAVES.md W1.4): {@code restore(checkpoint(w))} continues bit-identically to {@code w} and to
 * {@code w.fork()}, compared by hash every tick for 1,000 ticks, on a world with toy kinds, units, bullets, timers,
 * attachments, a derived part and a stream of inputs.
 */
class CheckpointTest{
    static World run(World world, int ticks){
        for(int i = 0; i < ticks; i++){
            Fixtures.feed(world, world.ticks());
            world.tick();
        }
        return world;
    }

    static byte[] file(World world){
        return Checkpoint.capture(world).encode(Compression.FAST);
    }

    @Test
    void aRestoredWorldContinuesLikeTheWorldAndItsFork(){
        World live = run(Fixtures.churning(11), 300);
        byte[] file = file(live);
        World fork = live.fork();
        World restored = Checkpoint.decode(file).restore(Fixtures.config(11));
        assertEquals(live.hash(), restored.hash());
        assertDerivedRebuilt(live, restored);
        assertArrayEquals(file, file(restored), "a restored world checkpoints to the same bytes");

        var exercised = new Exercised();
        for(int i = 0; i < 1000; i++){
            long tick = live.ticks();
            for(World world : List.of(live, fork, restored)) Fixtures.feed(world, tick);
            live.tick();
            fork.tick();
            restored.tick();
            WorldHash expected = live.hash();
            assertEquals(expected, fork.hash(), () -> "the fork diverged at tick " + tick);
            WorldHash actual = restored.hash();
            assertEquals(expected, actual, () -> "the restored world diverged at tick " + tick + " in " + expected.firstDifference(actual).orElseThrow());
            exercised.see(live);
            if(i % 250 == 249) assertArrayEquals(file(live), file(restored), "checkpoints differ at tick " + tick);
        }
        assertDerivedRebuilt(live, restored);
        assertEquals(verdicts(live.log().since(restored.log().start())), verdicts(restored.log().entries()), "the inputs got the same verdicts");
        exercised.check();
    }

    @Test
    void checkpointsRestoreFromAnyTick(){
        for(int ticks : new int[]{0, 1, 2, 57}){
            World live = run(Fixtures.churning(ticks + 20), ticks);
            World restored = Checkpoint.decode(file(live)).restore(Fixtures.config(ticks + 20));
            for(int i = 0; i < 60; i++){
                Fixtures.feed(live, live.ticks());
                Fixtures.feed(restored, restored.ticks());
                live.tick();
                restored.tick();
                assertEquals(live.hash(), restored.hash(), "from tick " + ticks + ", " + i + " ticks later");
            }
        }
    }

    @Test
    void aCaptureIsACopyThatTheWorldCanOutrun(){
        World live = run(Fixtures.churning(12), 120);
        WorldHash then = live.hash();
        Capture capture = Checkpoint.capture(live);
        run(live, 30);
        World restored = Checkpoint.decode(capture.encode(Compression.FAST)).restore(Fixtures.config(12));
        assertEquals(then, restored.hash(), "the capture kept the state of its tick");
        assertEquals(120, restored.ticks());
        assertEquals(120, capture.tick());
    }

    @Test
    void theHeaderDescribesTheCheckpoint(){
        World live = run(Fixtures.churning(13), 40);
        byte[] file = file(live);
        Header header = Checkpoint.header(file);
        assertEquals("checkpoint", header.kind());
        assertEquals(BuildInfo.id(), header.build());
        assertEquals(ToyContent.INSTANCE.hash(), header.content());
        assertEquals(40, header.tick());
        assertEquals(Checkpoint.systemsId(Fixtures.config(13)), header.systems());
        assertNotEquals(Checkpoint.systemsId(WorldConfig.of(ToyContent.INSTANCE, 48, 48, 13)), header.systems(), "the fixtures run more systems");
        Checkpoint checkpoint = Checkpoint.decode(file);
        assertEquals(live.log().position(), checkpoint.logPosition());
        WorldConfig config = checkpoint.config(ToyContent.INSTANCE);
        assertEquals(48, config.width());
        assertEquals(13, config.seed());
        assertInstanceOf(MemoryJournal.class, config.journal());
    }

    @Test
    void compressionIsOptionalAndTransparent(){
        World live = run(Fixtures.churning(14), 200);
        Capture capture = Checkpoint.capture(live);
        byte[] raw = capture.encode(Compression.NONE), fast = capture.encode(Compression.FAST), best = capture.encode(Compression.deflate(9));
        assertTrue(fast.length < raw.length, fast.length + " vs " + raw.length);
        assertTrue(best.length <= fast.length);
        for(byte[] file : List.of(raw, fast, best)){
            assertEquals(live.hash(), Checkpoint.decode(file).restore(Fixtures.config(14)).hash());
        }
        assertEquals(capture.rawBytes(), Checkpoint.decode(raw).sectionSizes().values().stream().mapToLong(Integer::longValue).sum());
    }

    @Test
    void theBuiltInSystemsRoundTripToo(){
        World live = Worlds.churned(15, 150);
        World restored = Checkpoint.decode(file(live)).restore(WorldConfig.of(ToyContent.INSTANCE, 48, 48, 15));
        for(int i = 0; i < 100; i++){
            live.tick();
            restored.tick();
            assertEquals(live.hash(), restored.hash(), "tick " + i);
        }
    }

    static void assertDerivedRebuilt(World live, World restored){
        assertArrayEquals(live.part(Fixtures.Census.class).counts, restored.part(Fixtures.Census.class).counts, "the derived census");
        for(int i = 0; i < live.buildings().indexLimit(); i++){
            Building building = live.buildings().get(i);
            if(building == null) continue;
            assertEquals(building.attachment(Fixtures.MARK), restored.buildings().get(i).attachment(Fixtures.MARK), "the derived mark of " + i);
        }
    }

    static List<String> verdicts(List<CommandLog.Entry> entries){
        return entries.stream().map(entry -> entry.tick() + " " + entry.input() + " " + entry.verdict()).toList();
    }

    /** Counts what the run went through, so a quiet run can't pass. */
    static final class Exercised{
        int units, bullets, timers, heated, links, staleLinks, rejected, admitted;

        void see(World world){
            units = Math.max(units, world.units().size());
            bullets = Math.max(bullets, world.bullets().size());
            timers = Math.max(timers, world.timers().pending().size());
            world.buildings().each(building -> {
                if(building.attachment(Fixtures.HEAT) != null) heated++;
                if(building instanceof Link link && link.target != null){
                    links++;
                    if(!link.target.isValid()) staleLinks++;
                }
            });
            for(CommandLog.Entry entry : world.log().since(Math.max(world.log().start(), world.log().position() - 3))){
                if(entry.tick() == world.ticks() - 1){
                    if(entry.verdict().admitted()) admitted++;
                    else rejected++;
                }
            }
        }

        void check(){
            String summary = "units " + units + ", bullets " + bullets + ", timers " + timers + ", heated " + heated + ", links "
                + links + " (" + staleLinks + " stale), inputs " + admitted + " admitted, " + rejected + " rejected";
            assertTrue(units > 3 && bullets > 3 && timers > 3 && heated > 0 && links > 0 && staleLinks > 0 && admitted > 0 && rejected > 0, summary);
        }
    }
}
