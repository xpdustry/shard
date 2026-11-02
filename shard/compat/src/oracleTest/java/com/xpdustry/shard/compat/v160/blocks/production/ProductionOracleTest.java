package com.xpdustry.shard.compat.v160.blocks.production;

import com.xpdustry.shard.oracle.*;
import mindustry.world.blocks.production.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate (WP35): drills, burst and beam drills, wall crafters, pumps, solid pumps and frackers as vanilla's, every frame
 * ({@link ProductionRun}): ore counting and the dominant item, warmup, progress and boosts, the dump timers, items
 * offloaded into conveyors and on to a core, liquids pumped into the blocks that take them, power, the random draws of
 * the update effects, and the save chunks; then vanilla's save of each fixture loaded on both sides and run on. Each
 * fixture ends by checking that what it is there for happened.
 */
class ProductionOracleTest{
    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    /**
     * Mechanical, pneumatic, laser and blast drills on mixed ore, with and without water (from a mechanical pump on
     * shallow water, or by hand), offloading into a conveyor line that ends in a core. Mid-run: an ore changes under a
     * drill, a drill is disabled for a while, a conveyor goes and comes back, a drill is overdriven and a power source
     * switched off. A laser drill with no outlet fills up and stops.
     */
    @Test
    void serpuloDrillsFeedACoreThroughConveyors(){
        var run = new ProductionRun(36, 24);
        run.place("core-shard", 31, 15);
        run.line("conveyor", 4, 15, 0, 26);

        // A: copper x2, lead, sand: copper; unboosted
        run.ore(6, 16, "ore-copper");
        run.ore(7, 16, "ore-copper");
        run.floor(6, 17, "sand-floor");
        run.ore(7, 17, "ore-lead");
        run.place("mechanical-drill", 6, 16);
        // A2: titanium x2 (too hard), copper, lead: lead wins the tie; boosted by a mechanical pump on shallow water
        run.ore(6, 13, "ore-titanium");
        run.ore(7, 13, "ore-titanium");
        run.ore(6, 14, "ore-copper");
        run.ore(7, 14, "ore-lead");
        run.place("mechanical-drill", 6, 13);
        run.floor(8, 13, "shallow-water");
        run.place("mechanical-pump", 8, 13);

        // B: coal x2, copper x2: coal wins the tie; boosted by a pump
        run.ore(11, 16, "ore-coal");
        run.ore(11, 17, "ore-coal");
        run.ore(12, 16, "ore-copper");
        run.ore(12, 17, "ore-copper");
        run.place("pneumatic-drill", 11, 16);
        run.floor(13, 17, "shallow-water");
        run.place("mechanical-pump", 13, 17);
        // B2: sand only
        run.floors(11, 13, 2, 2, "sand-floor");
        run.place("pneumatic-drill", 11, 13);

        // C: titanium x4, copper x3, thorium x2: titanium; powered, watered by hand
        String[] c = {"ore-titanium", "ore-titanium", "ore-titanium", "ore-titanium", "ore-copper", "ore-copper", "ore-copper", "ore-thorium", "ore-thorium"};
        for(int i = 0; i < 9; i++) run.ore(16 + i % 3, 16 + i / 3, c[i]);
        run.place("laser-drill", 17, 17);
        run.place("power-source", 19, 17);
        run.every(1, 600, 20, () -> run.liquid(17, 17, "water", 3f));
        // C2: sand x5 and scrap x4: scrap, as sand is low priority; powered, switched off later
        run.floors(16, 12, 3, 3, "sand-floor");
        for(int i = 0; i < 4; i++) run.ore(16 + i, 12, "ore-scrap");
        run.place("laser-drill", 17, 13);
        run.place("power-source", 19, 13);

        // D: a blast drill on thorium, powered, watered by hand
        for(int x = 23; x <= 26; x++) for(int y = 16; y <= 19; y++) run.ore(x, y, "ore-thorium");
        run.place("blast-drill", 24, 17);
        run.place("power-source", 27, 17);
        run.every(1, 600, 15, () -> run.liquid(24, 17, "water", 2f));

        // E: a laser drill on sand with no outlet
        run.floors(4, 4, 3, 3, "sand-floor");
        run.place("laser-drill", 5, 5);
        run.place("power-source", 7, 5);

        run.at(200, () -> run.ore(6, 17, "ore-lead"));
        run.at(250, () -> run.remove(12, 15));
        run.at(320, () -> run.place("conveyor", 12, 15, 0));
        run.at(300, () -> run.enabled(11, 16, false));
        run.at(360, () -> run.enabled(11, 16, true));
        run.at(400, () -> run.boost(17, 17, 2.5f, 120f));
        run.at(450, () -> run.enabled(19, 13, false));
        run.run(600);

        var a = (Drill.DrillBuild)run.vanilla(6, 16);
        assertEquals("lead", a.dominantItem.name, "the ore change moved the first drill to lead");
        assertEquals("lead", ((Drill.DrillBuild)run.vanilla(6, 13)).dominantItem.name, "a tie goes to the higher id");
        assertEquals("coal", ((Drill.DrillBuild)run.vanilla(11, 16)).dominantItem.name);
        assertEquals("scrap", ((Drill.DrillBuild)run.vanilla(17, 13)).dominantItem.name, "sand is low priority");
        assertEquals(10, run.vanilla(5, 5).items.total(), "the drill with no outlet filled up");
        var core = run.vanilla(31, 15);
        assertTrue(core.items.get(mindustry.content.Items.titanium) > 0 && core.items.get(mindustry.content.Items.thorium) > 0
            && core.items.get(mindustry.content.Items.coal) > 0, "drills fed the core: " + core.items);
    }

    /**
     * Erekir: an impact drill and an eruption drill on ore floors, powered and fed by hand, with and without their
     * boosts, into a conveyor line to a core; plasma bores facing beryllium and graphite walls (one beam each, then a
     * wall broken in a beam and another built behind it), and a large one on tungsten; cliff crushers facing sand walls,
     * the large one burning graphite.
     */
    @Test
    void erekirBurstAndBeamDrillsAndCrushers(){
        var run = new ProductionRun(40, 30);
        run.place("core-shard", 13, 4);
        run.line("conveyor", 5, 4, 0, 7);

        // impact drill on beryllium (2x faster), boosted with ozone for the first half
        for(int x = 5; x <= 8; x++) for(int y = 5; y <= 8; y++) run.ore(x, y, "ore-beryllium");
        run.place("impact-drill", 6, 6);
        run.place("power-source", 9, 6);
        run.every(1, 900, 10, () -> run.liquid(6, 6, "water", 2f));
        run.every(1, 450, 10, () -> run.liquid(6, 6, "ozone", 0.6f));
        // eruption drill on tungsten, on hydrogen, never boosted
        for(int x = 16; x <= 20; x++) for(int y = 6; y <= 10; y++) run.ore(x, y, "ore-tungsten");
        run.place("eruption-drill", 18, 8);
        run.place("power-source", 21, 8);
        run.every(1, 900, 10, () -> run.liquid(18, 8, "hydrogen", 0.8f));

        // plasma bore facing east: a beryllium ore wall and a graphitic wall, so no single item
        run.wall(25, 14, "beryllic-stone-wall");
        run.ore(25, 14, "ore-wall-beryllium");
        run.wall(24, 15, "graphitic-wall");
        run.place("plasma-bore", 20, 14, 0);
        run.place("power-source", 19, 14);
        run.every(1, 900, 30, () -> run.liquid(20, 14, "hydrogen", 0.2f));
        // plasma bore facing up: tungsten (too hard) and beryllium; the beryllium wall goes, another stands behind
        run.wall(26, 18, "beryllic-stone-wall");
        run.ore(26, 18, "ore-wall-tungsten");
        run.wall(27, 20, "beryllic-stone-wall");
        run.ore(27, 20, "ore-wall-beryllium");
        run.wall(27, 22, "beryllic-stone-wall");
        run.ore(27, 22, "ore-wall-beryllium");
        run.place("plasma-bore", 26, 16, 1);
        run.place("power-source", 25, 16);
        run.at(300, () -> run.remove(27, 20));
        // large plasma bore facing west, on tungsten, on hydrogen, boosted with nitrogen
        for(int y = 19; y <= 21; y++){
            run.wall(33, y, "beryllic-stone-wall");
            run.ore(33, y, "ore-wall-tungsten");
        }
        run.place("large-plasma-bore", 36, 20, 2);
        run.place("power-source", 38, 20);
        run.every(1, 900, 10, () -> {
            run.liquid(36, 20, "hydrogen", 0.2f);
            run.liquid(36, 20, "nitrogen", 0.6f);
        });

        // cliff crusher facing down: a sand wall and a dune wall
        run.wall(6, 19, "sand-wall");
        run.wall(7, 19, "dune-wall");
        run.place("cliff-crusher", 6, 20, 3);
        run.place("power-source", 8, 20);
        // large cliff crusher facing east, burning graphite, on hydrogen
        run.wall(14, 19, "regolith-wall");
        run.wall(14, 20, "sand-wall");
        run.place("large-cliff-crusher", 12, 20, 0);
        run.place("power-source", 12, 22);
        run.at(1, () -> run.items(12, 20, "graphite", 6));
        run.every(1, 900, 10, () -> run.liquid(12, 20, "hydrogen", 0.3f));
        run.at(500, () -> run.enabled(6, 20, false));
        run.at(560, () -> run.enabled(6, 20, true));
        run.run(900);

        assertTrue(run.vanilla(13, 4).items.get(mindustry.content.Items.beryllium) > 0, "the impact drill fed the core");
        assertTrue(run.vanilla(18, 8).items.get(mindustry.content.Items.tungsten) > 0, "the eruption drill drilled");
        var bore = (BeamDrill.BeamDrillBuild)run.vanilla(20, 14);
        assertTrue(bore.items.get(mindustry.content.Items.beryllium) > 0 && bore.items.get(mindustry.content.Items.graphite) > 0,
            "the bore mined both walls: " + bore.items);
        assertTrue(run.vanilla(26, 16).items.get(mindustry.content.Items.beryllium) > 0, "the upward bore mined beryllium");
        assertTrue(run.vanilla(36, 20).items.get(mindustry.content.Items.tungsten) > 0, "the large bore mined tungsten");
        assertTrue(run.vanilla(6, 20).items.get(mindustry.content.Items.sand) > 0, "the crusher made sand");
        assertTrue(run.vanilla(12, 20).items.get(mindustry.content.Items.graphite) < 6, "the large crusher burned graphite");
    }

    /**
     * Pumps, as maps use them: a mechanical pump on shallow water through conduits into a liquid tank, and one on deep
     * water into a wave turret; a rotary pump on deep water through conduits into a liquid void (half of it on shallow
     * water and stone later, its first conduit gone later); an impulse pump on tar with nowhere to put its oil; water
     * extractors on stone and salt, one through conduits into a liquid container, one with a water tile under it, one
     * watering a mechanical drill; an oil extractor on sand and shale, fed sand by a source and water by a pump that
     * can't keep up, filling a wave turret with oil; and an overdriven pump emptying into a void, whose consume timer
     * overshoots its period.
     */
    @Test
    void pumpsExtractorsAndAFracker(){
        var run = new ProductionRun(32, 24);
        run.floor(1, 2, "shallow-water");
        run.place("mechanical-pump", 1, 2);
        run.line("conduit", 2, 2, 0, 4);
        run.place("liquid-tank", 7, 2);
        run.floor(1, 6, "deep-water");
        run.place("mechanical-pump", 1, 6);
        run.place("wave", 2, 6);

        run.floors(11, 2, 2, 2, "deep-water");
        run.place("rotary-pump", 11, 2);
        run.place("power-source", 11, 4);
        run.line("conduit", 13, 2, 0, 2);
        run.place("liquid-void", 15, 2);
        run.at(200, () -> run.floor(12, 3, "shallow-water"));
        run.at(260, () -> run.floor(12, 2, "stone"));
        var flowing = new boolean[1];
        // a conduit hands water on to the void within the tick, so the pump shows the flow: still pumping, never full
        run.at(320, () -> {
            var pump = (Pump.PumpBuild)run.vanilla(11, 2);
            flowing[0] = pump.efficiency > 0f && pump.warmup > 0.9f && pump.liquids.get(mindustry.content.Liquids.water) < 40f;
        });
        run.at(330, () -> run.remove(13, 2));

        run.floors(18, 2, 3, 3, "tar");
        run.place("impulse-pump", 19, 3);
        run.place("power-source", 21, 3);

        // water extractors: stone x3 and salt, into a container; stone x3 and shallow water; and one watering a drill
        run.floor(4, 11, "salt");
        run.place("water-extractor", 3, 10);
        run.place("power-source", 2, 10);
        run.line("conduit", 5, 10, 0, 3);
        run.place("liquid-container", 8, 10);
        run.floor(13, 11, "shallow-water");
        run.place("water-extractor", 12, 10);
        run.place("power-source", 11, 10);
        run.ore(19, 10, "ore-copper");
        run.ore(20, 10, "ore-copper");
        run.ore(19, 11, "ore-copper");
        run.ore(20, 11, "ore-copper");
        run.place("mechanical-drill", 19, 10);
        run.place("water-extractor", 17, 10);
        run.place("power-source", 17, 12);
        run.at(250, () -> run.enabled(17, 12, false));
        run.at(300, () -> run.enabled(17, 12, true));

        // the oil extractor: sand x6, shale x3; sand from a source, oil into a wave, and water from a mechanical pump,
        // which can't keep up with it, so it works below full efficiency
        run.floors(19, 13, 3, 2, "sand-floor");
        run.floors(19, 15, 3, 1, "shale");
        run.place("oil-extractor", 20, 14);
        run.place("power-source", 18, 14);
        run.place("item-source", 20, 16);
        run.source(20, 16, "sand");
        run.place("wave", 22, 14);
        run.floor(18, 13, "shallow-water");
        run.place("mechanical-pump", 18, 13);
        run.at(400, () -> run.floor(19, 13, "stone"));
        var starved = new boolean[1];
        run.every(10, 600, 10, () -> {
            float efficiency = run.vanilla(20, 14).efficiency;
            if(efficiency > 0f && efficiency < 1f) starved[0] = true;
        });

        // an overdriven pump emptying into a void, so it never fills
        run.floor(26, 20, "shallow-water");
        run.place("mechanical-pump", 26, 20);
        run.place("conduit", 27, 20, 0);
        run.place("liquid-void", 28, 20);
        run.at(1, () -> run.boost(26, 20, 2.3f, 1000f));
        var overdriven = new boolean[1];
        run.at(500, () -> {
            var pump = (Pump.PumpBuild)run.vanilla(26, 20);
            overdriven[0] = pump.timeScale() > 2f && pump.warmup > 0.9f && pump.efficiency > 0f;
        });
        run.run(600);

        var water = mindustry.content.Liquids.water;
        assertTrue(run.vanilla(7, 2).liquids.get(water) > 0, "the pump filled the tank through its conduits");
        assertTrue(run.vanilla(2, 6).liquids.get(water) > 0, "the pump filled the wave");
        assertTrue(flowing[0], "the rotary pump's conduits carried water to the void");
        assertTrue(run.vanilla(19, 3).liquids.get(mindustry.content.Liquids.oil) > 0, "the impulse pump pumped tar");
        assertTrue(run.vanilla(8, 10).liquids.get(water) > 0, "the extractor filled the container");
        assertTrue(run.vanilla(19, 10).liquids.get(water) > 0, "the extractor watered the drill");
        assertTrue(run.vanilla(22, 14).liquids.get(mindustry.content.Liquids.oil) > 0, "the oil extractor filled the wave with oil");
        assertTrue(starved[0], "the oil extractor ran short of water, below full efficiency");
        assertTrue(overdriven[0], "the overdriven pump was still pumping at frame 500");
    }
}
