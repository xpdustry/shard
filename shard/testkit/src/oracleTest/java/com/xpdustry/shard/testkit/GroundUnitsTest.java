package com.xpdustry.shard.testkit;

import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The documented exception: ground units walk vanilla's flowfields, which its {@code Pathfinder} threads build on
 * their own clock. Two vanilla runs may then disagree on where the units are; {@link Tolerances#unitPaths} accepts
 * exactly that and nothing more.
 */
class GroundUnitsTest{
    /**
     * Crux daggers walking toward a sharded core 30 tiles away: far enough that nobody shoots within the scenario.
     * With {@code wait}, the scenario waits for vanilla's first flowfield before the daggers move.
     */
    static Scenario march(boolean wait){
        var s = Scenario.named(wait ? "dagger-march" : "dagger-march-nowait").world(48, 48).seed(5)
            // crux has no core: without a cap its daggers die on spawn. With waves on (the timer off), crux is the
            // active wave team (Teams.updateEnemies) and an AI team (Team.isAI), so its daggers get GroundAI and see
            // the sharded core as an enemy, instead of idling under CommandAI
            .rules(Map.of("unitCap", 8, "waves", true, "waveTimer", false))
            .place("core-shard", 42, 42)
            .team("crux").spawn("dagger", 40f, 40f).spawn("dagger", 56f, 40f).spawn("dagger", 40f, 56f);
        // Before the first tick, not after it: the wait asks for the flowfield (and so starts it) and blocks until
        // vanilla's pathfinder thread has built it. After step(1), tick 1 raced that thread: a run whose thread was
        // done by then rotated its daggers 5 degrees at tick 1 and one whose thread wasn't did not, which a loaded
        // machine made likely (the full check failed on it once, at tick 1).
        if(wait) s.waitForPaths();
        return s.step(300).build();
    }

    @Test
    void theDaggersWalk(){
        Projection end = OracleRunner.create().run(march(true));
        assertEquals(3, end.units().size(), "all three alive");
        for(Projection.UnitState dagger : end.units()){
            assertTrue(dagger.x() > 64f || dagger.y() > 64f, "it walked toward the core: " + dagger);
        }
    }

    /** Waiting for the first flowfield takes the wall clock out of this scenario: the runs agree exactly. */
    @Test
    void waitingForPathsMakesTheMarchExact(){
        DiffReport report = Differential.of(march(true)).reference(OracleRunner.create()).candidate(OracleRunner.create()).trace().run();
        System.out.println(report.text());
        report.assertNoDiff();
    }

    /**
     * Without the wait, the daggers start walking whenever the pathfinder thread is done, in wall time: the
     * documented exception. Only their positions and rotations may differ, and {@link Tolerances#unitPaths} accepts
     * exactly those.
     */
    @Test
    void withoutWaitingOnlyDaggerPathsMayDiffer(){
        DiffReport exact = Differential.of(march(false)).reference(OracleRunner.create()).candidate(OracleRunner.create()).trace().run();
        System.out.println("exact, for the record (it may or may not diverge):\n" + exact.text());
        for(Difference d : exact.differences()){
            assertTrue(d.entity().startsWith("unit#") && "dagger".equals(d.label()) && Set.of("x", "y", "rotation").contains(d.path()),
                "only dagger positions may differ between vanilla runs: " + d);
        }
        DiffReport tolerated = Differential.of(march(false)).reference(OracleRunner.create()).candidate(OracleRunner.create())
            .tolerances(Tolerances.exact().unitPaths("dagger")).trace().run();
        System.out.println(tolerated.text());
        tolerated.assertNoDiff();
    }
}
