package com.xpdustry.shard.testkit;

import com.xpdustry.shard.testkit.toy.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Trace mode finds the first diverging tick; the other modes bracket it. */
class TraceTest{
    /** A failure detector for a trace that doesn't end, far past anything a loaded machine took. */
    static final java.time.Duration HANG = java.time.Duration.ofMinutes(5);

    @TempDir
    Path out;

    @Test
    void aBrokenToyKindIsCaughtAtTheRightTick(){
        DiffReport report = Differential.of(Toys.pumps(400)).reference(Toys.shard()).candidate(Toys.broken())
            .output(out).trace().run();
        report.assertDivergesAt(BrokenPump.BROKEN_STROKE);
        assertEquals(99, report.lastAgreed());
        assertEquals(101, report.checkpoints(), "ticks 0..100");
        // the second pump was removed at tick 100, before the checkpoint: only the first one shows the bug
        var water = report.at("building(2,2).liquids.water");
        assertEquals(1, report.differences().size(), report.text());
        assertEquals(1, water.size());
        assertEquals(25f, water.getFirst().reference());
        assertEquals(26f, water.getFirst().candidate());
        assertEquals(24.75f, water.getFirst().before(), "the reference value at tick 99");
        assertEquals("pump", water.getFirst().label());

        String text = report.text();
        assertTrue(text.startsWith("FAIL scenario 'toy-pumps': shard (reference) vs shard (broken) (candidate), trace"), text);
        assertTrue(text.contains("first divergence at tick 100 (tick 99 agreed)"), text);
        assertTrue(text.contains("building(2,2).liquids.water"), text);
        assertTrue(text.contains("not compared: core, wave (not projected by shard, shard (broken))"), text);

        Path dumps = report.dumps();
        assertNotNull(dumps);
        for(String file : List.of("tick-100.reference.txt", "tick-100.candidate.txt", "tick-99.reference.txt", "tick-99.candidate.txt",
            "tick-100.reference.json", "tick-100.candidate.json", "report.txt")){
            assertTrue(Files.exists(dumps.resolve(file)), file);
        }
        assertTrue(read(dumps.resolve("tick-100.candidate.txt")).contains("building(2,2).liquids.water = 26.0"));
    }

    @Test
    void theRecordedPathFindsTheSameTick(){
        // Two "global" runners can't run side by side, as two vanilla runs can't: the reference is recorded and replayed.
        DiffReport report = Differential.of(Toys.pumps(400)).reference(Toys.global(Toys.shard())).candidate(Toys.global(Toys.broken()))
            .output(out).trace().run();
        report.assertDivergesAt(BrokenPump.BROKEN_STROKE);
        assertEquals(List.of("building(2,2).liquids.water"), report.differences().stream().map(Difference::where).toList());
        assertEquals(24.75f, report.differences().getFirst().before());
        assertFalse(Files.exists(report.dumps().resolve("tick-100.reference.json")), "the recorded side has no structured projection");
        assertTrue(Files.exists(report.dumps().resolve("tick-100.reference.txt")));
    }

    @Test
    void coarserModesBracketTheDivergence(){
        DiffReport atEnd = Differential.of(Toys.pumps(400)).reference(Toys.shard()).candidate(Toys.broken()).output(out).run();
        atEnd.assertDivergesAt(400);
        assertEquals(-1, atEnd.lastAgreed());

        DiffReport every = Differential.of(Toys.pumps(400)).reference(Toys.shard()).candidate(Toys.broken()).output(out).every(30).run();
        every.assertDivergesAt(120);
        assertEquals(90, every.lastAgreed());
        assertTrue(every.text().contains("rerun with trace() to find the exact tick"), every.text());
    }

    @Test
    void identicalRunsAgreeInEveryMode(){
        for(Differential d : List.of(
            Differential.of(Toys.pumps(300)).reference(Toys.shard()).candidate(Toys.shard()).trace(),
            Differential.of(Toys.pumps(300)).reference(Toys.global(Toys.shard())).candidate(Toys.global(Toys.shard())).trace(),
            Differential.of(Toys.pumps(300)).reference(Toys.shard()).candidate(Toys.shard()).every(7))){
            DiffReport report = d.output(out).run().assertNoDiff();
            assertTrue(report.text().startsWith("OK scenario 'toy-pumps'"), report.text());
            assertTrue(report.text().contains("shard (2nd run)"), report.text());
        }
    }

    @Test
    void toleranceRulesCanAcceptTheBug(){
        Differential.of(Toys.pumps(400)).reference(Toys.shard()).candidate(Toys.broken()).output(out).trace()
            .tolerances(Tolerances.exact().abs("building[pump].liquids", 1.5))
            .run().assertNoDiff();
        Differential.of(Toys.pumps(400)).reference(Toys.shard()).candidate(Toys.broken()).output(out).trace()
            .tolerances(Tolerances.exact().ignore("building(2,2).liquids.water"))
            .run().assertNoDiff();
        Differential.of(Toys.pumps(400)).reference(Toys.shard()).candidate(Toys.broken()).output(out).trace()
            .tolerances(Tolerances.exact().ignore("building").exactly("building[kind=toy-pump].liquids"))
            .run().assertDivergesAt(100);
    }

    /**
     * A 10k-tick trace, every tick projected and compared on both sides. The time is printed, and only a hang fails on it:
     * alone this takes a few seconds and in the full check it has passed a 30 s budget, so a budget in seconds measured the
     * machine. {@code 10_001} checkpoints say the whole trace ran; the failure detector says it finished.
     */
    @Test
    void tenThousandTickTracesAreCheap(){
        var scenario = Scenario.named("long-trace").world(64, 64).seed(1);
        for(int x = 1; x < 60; x += 2){
            scenario.place("pump", x, 10).place("counter", x, 20, 0, Config.item("copper")).place("copper-wall", x, 30);
        }
        for(int i = 0; i < 10; i++) scenario.spawn("dagger", 80f + i * 16f, 400f);
        Scenario s = scenario.step(10_000).build();
        for(Differential d : List.of(Differential.of(s).reference(Toys.shard()).candidate(Toys.shard()),
            Differential.of(s).reference(Toys.global(Toys.shard())).candidate(Toys.global(Toys.shard())))){
            long start = System.nanoTime();
            DiffReport report = assertTimeoutPreemptively(HANG, () -> d.output(out).trace().run().assertNoDiff());
            double seconds = (System.nanoTime() - start) / 1e9;
            System.out.printf(Locale.ROOT, "10k-tick trace, 90 buildings, 10 units: %.2f s (%s)%n", seconds, report.text().strip());
            assertEquals(10_001, report.checkpoints());
        }
    }

    private static String read(Path file){
        try{
            return Files.readString(file);
        }catch(java.io.IOException e){
            throw new java.io.UncheckedIOException(e);
        }
    }
}
