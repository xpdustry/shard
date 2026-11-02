package com.xpdustry.shard.sim.persist;

import com.xpdustry.shard.sim.toy.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** A crash bundle replays to the same hashes, verdicts and error, and replay names the first difference. */
class CrashBundleTest{
    static final long CHECKPOINT = 100, FAILS = 250;

    /** Throws in the building phase of one tick, after that tick's inputs were admitted. */
    public static final class Bomb implements PhaseHandler{
        @Override
        public Phase phase(){
            return Phase.BUILDINGS;
        }

        @Override
        public void run(World world){
            if(world.ticks() == FAILS) throw new IllegalStateException("boom at " + world.ticks());
        }
    }

    static WorldConfig config(){
        var handlers = new ArrayList<PhaseHandler>();
        for(Phase phase : Phase.values()) handlers.addAll(Fixtures.config(50).systems().handlers(phase));
        handlers.add(new Bomb());
        return Fixtures.config(50).withSystems(Fixtures.config(50).systems().withHandlers(handlers));
    }

    /** What a runtime does: checkpoint, keep a hash trail, and bundle up when the tick throws. */
    static CrashBundle crash(){
        var world = new World(config());
        world.part(Chaos.class).enabled = true;
        CheckpointTest.run(world, (int)CHECKPOINT);
        byte[] checkpoint = Checkpoint.capture(world).encode(Compression.FAST);
        var trail = new ArrayList<CrashBundle.Mark>();
        trail.add(new CrashBundle.Mark(world.ticks(), world.hash()));
        try{
            while(true){
                Fixtures.feed(world, world.ticks());
                if(world.ticks() == FAILS) world.submit(new Fixtures.Wipe(3, 3), new Cause.Admin("last"));
                world.tick();
                if(world.ticks() % 10 == 0) trail.add(new CrashBundle.Mark(world.ticks(), world.hash()));
            }
        }catch(IllegalStateException error){
            assertEquals(FAILS, world.ticks());
            return CrashBundle.of(checkpoint, world, trail, world.ticks(), error, new TreeMap<>(Map.of("world", "test")));
        }
    }

    @Test
    void aReplayReproducesTheHashesAndTheFailure(){
        CrashBundle written = crash();
        CrashBundle bundle = CrashBundle.decode(written.encode(), ToyContent.INSTANCE);
        assertEquals(written.entries(), bundle.entries());
        assertEquals(written.hashes(), bundle.hashes());
        assertEquals(written.logStart(), bundle.logStart());
        assertEquals("java.lang.IllegalStateException: boom at 250", bundle.errorSummary());
        assertEquals("test", bundle.labels().get("world"));
        assertFalse(bundle.entries().isEmpty());
        assertEquals(FAILS, bundle.entries().getLast().tick(), "the failed tick's admissions are in the log");

        var out = new ArrayList<String>();
        Replay.Result result = Replay.run(bundle, config(), Replay.Options.DEFAULT, out::add);
        assertTrue(result.ok(), result + "\n" + String.join("\n", out));
        assertTrue(result.reproduced());
        assertEquals(CHECKPOINT, result.from());
        assertEquals(FAILS, result.to());
        assertEquals(bundle.hashes().size(), result.hashes());
        assertEquals(bundle.entries().size(), result.inputs());
    }

    @Test
    void replayStopsAtUntilAndPrintsHashes(){
        CrashBundle bundle = crash();
        var out = new ArrayList<String>();
        Replay.Result result = Replay.run(bundle, config(), new Replay.Options(180, 20, false), out::add);
        assertTrue(result.ok(), result.toString());
        assertFalse(result.reproduced());
        assertEquals(180, result.to());
        assertTrue(out.stream().anyMatch(line -> line.startsWith("tick 180 ")), out.toString());
        assertEquals(4, out.stream().filter(line -> line.startsWith("tick ")).count(), out.toString());
    }

    @Test
    void replayNamesTheSubsystemThatDiverged(){
        CrashBundle bundle = crash();
        var hashes = new ArrayList<>(bundle.hashes());
        CrashBundle.Mark mark = hashes.get(5);
        var sections = new ArrayList<WorldHash.Section>();
        for(WorldHash.Section section : mark.hash().sections()){
            sections.add(section.name().equals("bullets") ? new WorldHash.Section("bullets", section.hash() + 1) : section);
        }
        hashes.set(5, new CrashBundle.Mark(mark.tick(), WorldHash.of(sections)));
        var tampered = new CrashBundle(bundle.checkpoint(), bundle.logStart(), bundle.entries(), hashes, bundle.failedTick(),
            bundle.error(), bundle.labels());
        Replay.Result result = Replay.run(tampered, config(), Replay.Options.DEFAULT, line -> {});
        assertFalse(result.ok());
        assertEquals(mark.tick(), result.to());
        assertTrue(result.divergence().contains("first in bullets"), result.divergence());
    }

    @Test
    void aDifferentVerdictOrErrorIsADivergence(){
        CrashBundle bundle = crash();
        var entries = new ArrayList<>(bundle.entries());
        int index = 3;
        CommandLog.Entry entry = entries.get(index);
        Verdict flipped = entry.verdict().admitted() ? Verdict.rejected("made up") : Verdict.ADMITTED;
        entries.set(index, new CommandLog.Entry(entry.tick(), entry.arrival(), entry.input(), flipped));
        var wrongVerdict = new CrashBundle(bundle.checkpoint(), bundle.logStart(), entries, bundle.hashes(), bundle.failedTick(),
            bundle.error(), bundle.labels());
        Replay.Result verdict = Replay.run(wrongVerdict, config(), Replay.Options.DEFAULT, line -> {});
        assertTrue(verdict.divergence().startsWith("input 3 at tick " + entry.tick()), verdict.divergence());

        var wrongError = new CrashBundle(bundle.checkpoint(), bundle.logStart(), bundle.entries(), bundle.hashes(), bundle.failedTick(),
            "java.lang.IllegalStateException: something else\n", bundle.labels());
        Replay.Result error = Replay.run(wrongError, config(), Replay.Options.DEFAULT, line -> {});
        assertFalse(error.ok());
        assertFalse(error.reproduced());
        assertTrue(error.divergence().contains("boom at 250"), error.divergence());
    }

    @Test
    void replayRefusesOtherSystems(){
        CrashBundle bundle = crash();
        String message = assertThrows(CheckpointException.class,
            () -> Replay.run(bundle, Fixtures.config(50), Replay.Options.DEFAULT, line -> {})).getMessage();
        assertTrue(message.contains("other systems"), message);
    }
}
