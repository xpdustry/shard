package com.xpdustry.shard.testkit;

import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate: two vanilla runs of one scenario agree on every leaf, every tick. Vanilla can't run twice side by side, so
 * the differential records the first run and replays it against the second ({@link Trace}).
 */
class VanillaAgainstVanillaTest{
    /** A failure detector for a trace that doesn't end, far past anything a loaded machine took. */
    static final java.time.Duration HANG = java.time.Duration.ofMinutes(10);

    /** Items, crafting, liquids, power, configs and flying units on an empty world. */
    static Scenario factory(long ticks){
        return Scenario.named("vanilla-factory").world(40, 24).seed(3)
            // a team without a core has a unit cap of 0, and vanilla kills units over the cap on add (UnitComp.java:605)
            .rule("unitCap", 16)
            // copper → conveyors → router → sorter → container, and up from the router into a second container
            // (a 2×2 block placed at (x, y) covers x..x+1, y..y+1)
            .place("item-source", 1, 4, 0, Config.item("copper"))
            .row("conveyor", 2, 9, 4, 0)
            .place("router", 10, 4)
            .place("sorter", 11, 4, 0, Config.item("copper"))
            .place("container", 12, 4)
            .column("titanium-conveyor", 10, 5, 6, 1)
            .place("container", 10, 7)
            .place("item-source", 1, 9, 0, Config.item("lead"))
            .place("bridge-conveyor", 2, 9, 0, Config.point(3, 0))
            .place("bridge-conveyor", 5, 9)
            .row("conveyor", 6, 8, 9, 0)
            .place("item-void", 9, 9)
            // coal → graphite press → conveyor → container
            .place("item-source", 2, 14, 0, Config.item("coal"))
            .place("graphite-press", 3, 14)
            .row("conveyor", 5, 7, 14, 0)
            .place("container", 8, 14)
            // water → conduits → liquid router → liquid container
            .place("liquid-source", 1, 19, 0, Config.liquid("water"))
            .row("conduit", 2, 6, 19, 0)
            .place("liquid-router", 7, 19)
            .place("liquid-container", 8, 19)
            // power: solar panels charging batteries through a node
            .place("solar-panel", 20, 4).place("solar-panel", 21, 4)
            .place("battery", 22, 4)
            .place("power-node", 20, 8, 0, Config.points(new Config.Point(0, -4)))
            .place("battery", 21, 8)
            // flying units, which don't depend on the pathfinder threads
            .spawn("flare", 240f, 120f, 45f)
            .spawn("mono", 260f, 140f)
            .step(300)
            .items(12, 4, "lead", 20)
            .configure(11, 4, Config.item("lead"))
            .step(300)
            .remove(6, 4)
            .step(ticks - 600)
            .build();
    }

    @Test
    void theFactoryIsBusy(){
        Projection end = OracleRunner.create().run(factory(1200));
        assertTrue(end.building(12, 4).items().getOrDefault("copper", 0) > 0, "copper went through the sorter");
        assertTrue(end.building(10, 7).items().getOrDefault("copper", 0) > 0, "the router split copper up");
        assertEquals(20, end.building(12, 4).items().get("lead"), "the scripted lead");
        assertTrue(end.building(8, 14).items().getOrDefault("graphite", 0) > 0, "the press crafted");
        assertTrue(end.building(8, 19).liquids().getOrDefault("water", 0f) > 0f, "water reached the tank");
        assertTrue(end.building(22, 4).power().status() > 0f, "the battery charged");
        assertNull(end.building(6, 4), "the scripted removal");
        assertEquals(java.util.List.of("flare", "mono"), end.units().stream().map(Projection.UnitState::type).toList());
        assertEquals(16, end.rules().get("unitCap"));
        assertEquals(1, end.waves().wave());
        assertEquals(false, end.rules().get("canGameOver"));
    }

    @Test
    void aFactoryLayoutAgreesOnEveryTick(){
        DiffReport report = Differential.of(factory(1200))
            .reference(OracleRunner.create()).candidate(OracleRunner.create())
            .trace().run();
        System.out.println(report.text());
        report.assertNoDiff();
        assertEquals(1201, report.checkpoints());
        assertTrue(report.notes().isEmpty(), "vanilla projects every section: " + report.notes());
    }

    @Test
    void aNoOpScenarioOnABundledMapAgrees(){
        Scenario idle = Scenario.named("fortress-idle").map("fortress").step(1200).build();
        Projection end = OracleRunner.create().run(idle);
        System.out.println("fortress after 1200 ticks: " + end.buildings().size() + " buildings, " + end.units().size()
            + " units, cores " + end.cores() + ", " + end.waves());
        assertFalse(end.buildings().isEmpty());
        assertNotNull(end.cores().get("sharded"), "the player's core");
        assertTrue(end.waves().wavetime() > 0, "no wave yet: " + end.waves());

        DiffReport report = Differential.of(idle).reference(OracleRunner.create()).candidate(OracleRunner.create()).trace().run();
        System.out.println(report.text());
        report.assertNoDiff();
    }

    /**
     * A 10k-tick trace of both vanilla runs, every tick projected and compared. The time is printed, and only a hang fails
     * on it: a budget in seconds measures how busy the machine is.
     */
    @Test
    void tenThousandTicksTraceInCi(){
        long start = System.nanoTime();
        DiffReport report = assertTimeoutPreemptively(HANG, () -> Differential.of(factory(10_000)).reference(OracleRunner.create())
            .candidate(OracleRunner.create()).trace().run());
        double seconds = (System.nanoTime() - start) / 1e9;
        System.out.printf(Locale.ROOT, "vanilla 10k-tick trace, both runs: %.1f s%n%s", seconds, report.text());
        report.assertNoDiff();
    }
}
