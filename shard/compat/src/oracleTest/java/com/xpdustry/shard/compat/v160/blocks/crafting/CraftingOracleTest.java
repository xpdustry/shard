package com.xpdustry.shard.compat.v160.blocks.crafting;

import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.content.load.*;
import com.xpdustry.shard.oracle.*;
import mindustry.content.*;
import mindustry.world.blocks.production.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate (WP34): crafters, separators and incinerators as vanilla's, every frame ({@link CraftRun}): items and liquids
 * in and out, progress, warmup, accumulators, timers, power, the random's draws, the separators' seeds and picks, then
 * the save chunks, the block snapshots, and vanilla's save loaded by both and run on.
 */
class CraftingOracleTest{
    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    /**
     * A graphite press, two silicon smelters and a kiln fed by conveyors from item sources, dumping into conveyors that
     * end in containers; one smelter on a power source, the other on a battery that runs out. The press's source stops
     * for a while, its output belt is cut so it fills up and stops, then mended; a smelter is disabled for a while.
     */
    @Test
    void pressesAndSmeltersRunOffConveyorsAndPower(){
        var run = new CraftRun(30, 20);
        run.place("item-source", 1, 2).source(1, 2, "coal").line("conveyor", 2, 2, 0, 4).place("graphite-press", 6, 2)
            .line("conveyor", 8, 2, 0, 3).place("container", 11, 2);

        run.place("item-source", 1, 6).source(1, 6, "coal").line("conveyor", 2, 6, 0, 4);
        run.place("item-source", 1, 7).source(1, 7, "sand").line("conveyor", 2, 7, 0, 4);
        run.place("silicon-smelter", 6, 6).place("power-source", 6, 5).line("conveyor", 8, 6, 0, 3).place("container", 11, 6);

        run.place("item-source", 1, 11).source(1, 11, "coal").line("conveyor", 2, 11, 0, 4);
        run.place("item-source", 1, 12).source(1, 12, "sand").line("conveyor", 2, 12, 0, 4);
        run.place("silicon-smelter", 6, 11).place("battery", 6, 10).line("conveyor", 8, 11, 0, 3).place("container", 11, 11);
        run.status(6, 10, 0.05f);

        run.place("item-source", 1, 15).source(1, 15, "lead").line("conveyor", 2, 15, 0, 4);
        run.place("item-source", 1, 16).source(1, 16, "sand").line("conveyor", 2, 16, 0, 4);
        run.place("kiln", 6, 15).place("power-source", 6, 14).line("conveyor", 8, 15, 0, 3).place("container", 11, 15);

        run.at(200, () -> run.enabled(1, 2, false));
        run.at(260, () -> run.enabled(1, 2, true));
        run.at(400, () -> run.remove(9, 2));
        run.at(500, () -> run.enabled(6, 6, false));
        run.at(560, () -> run.enabled(6, 6, true));
        run.at(700, () -> run.place("conveyor", 9, 2));
        run.run(900);

        var vanilla = run.vanilla(11, 2);
        assertTrue(vanilla.items.get(Items.graphite) >= 5, "graphite reached the container: " + vanilla.items.get(Items.graphite));
        assertTrue(run.vanilla(11, 6).items.get(Items.silicon) >= 10, "silicon reached the container");
        assertTrue(run.vanilla(11, 15).items.get(Items.metaglass) >= 10, "metaglass reached the container");
        assertEquals(0f, run.vanilla(6, 10).power.status, "the battery ran out");
        assertTrue(run.snapshots > 0, "block snapshots compared");
    }

    /**
     * Liquids between crafters: a melter's slag into a separator, a spore press's oil through conduits into a coal
     * centrifuge, and an electrolyzer on a water source, its ozone out of its top into an oxidation chamber and its
     * hydrogen out of its bottom through a conduit into a liquid container, each only its own way ({@code liquidOutputDirections});
     * a pulverizer beside. The melter stops for a while and the water source is cleared.
     */
    @Test
    void liquidsFlowBetweenCrafters(){
        var run = new CraftRun(24, 26);
        run.place("item-source", 1, 2).source(1, 2, "scrap").place("melter", 2, 2).place("power-source", 2, 1);
        run.place("separator", 3, 2).place("power-source", 3, 1).place("container", 5, 2);

        run.place("item-source", 1, 7).source(1, 7, "spore-pod").place("spore-press", 2, 7).place("power-source", 2, 6);
        run.line("conduit", 4, 7, 0, 2).place("coal-centrifuge", 6, 7).place("power-source", 6, 6).place("container", 8, 7);

        run.place("electrolyzer", 3, 16).place("power-source", 1, 16).place("liquid-source", 1, 15).liquidSource(1, 15, "water");
        run.place("oxidation-chamber", 3, 19).place("power-source", 1, 19).place("item-source", 5, 19).source(5, 19, "beryllium")
            .place("container", 5, 20);
        run.place("conduit", 3, 14, 3).place("liquid-container", 3, 12);
        run.at(450, () -> run.liquidSource(1, 15, null));

        run.place("item-source", 9, 2).source(9, 2, "scrap").place("pulverizer", 10, 2).place("power-source", 10, 1).place("container", 11, 2);

        run.at(300, () -> run.enabled(2, 2, false));
        run.at(360, () -> run.enabled(2, 2, true));
        run.run(900);

        assertTrue(run.vanilla(8, 7).items.get(Items.coal) > 0, "the centrifuge made coal from the press's oil");
        assertTrue(run.vanilla(5, 20).items.get(Items.oxide) > 0, "the chamber made oxide from the electrolyzer's ozone");
        assertTrue(run.vanilla(3, 12).liquids.get(Liquids.hydrogen) > 1f, "hydrogen went down the conduit");
        assertEquals(0f, run.vanilla(3, 12).liquids.get(Liquids.ozone), "and no ozone");
        assertTrue(run.vanilla(11, 2).items.get(Items.sand) > 0, "the pulverizer made sand");
    }

    /**
     * A separator on a melter's slag and a disassembler on scrap and slag, over 2,000 frames: every pick from their
     * seeded generators lands where vanilla's does. The separator is disabled for a while, then its container goes and it
     * fills up.
     */
    @Test
    void separatorsPickExactlyOver2000Frames(){
        var run = new CraftRun(20, 16);
        run.place("item-source", 2, 3).source(2, 3, "scrap").place("melter", 3, 3).place("power-source", 3, 2);
        run.place("separator", 4, 3).place("power-source", 4, 2).place("container", 6, 3);

        run.place("item-source", 2, 9).source(2, 9, "scrap").place("disassembler", 4, 9);
        run.place("melter", 4, 7).place("item-source", 5, 7).source(5, 7, "scrap").place("power-source", 3, 7);
        run.place("container", 6, 9);

        run.at(1000, () -> run.enabled(4, 3, false));
        run.at(1100, () -> run.enabled(4, 3, true));
        run.at(1500, () -> run.remove(6, 3));
        run.run(2000);

        var separator = (Separator.SeparatorBuild)run.vanilla(4, 3);
        var disassembler = (Separator.SeparatorBuild)run.vanilla(4, 9);
        int seed = arc.math.Mathf.randomSeed(arc.math.geom.Point2.pack(4, 3), 0, Integer.MAX_VALUE - 1);
        assertTrue(separator.seed - seed > 30, "the separator picked " + (separator.seed - seed) + " times");
        int dSeed = arc.math.Mathf.randomSeed(arc.math.geom.Point2.pack(4, 9), 0, Integer.MAX_VALUE - 1);
        assertTrue(disassembler.seed - dSeed > 80, "the disassembler picked " + (disassembler.seed - dSeed) + " times");
        var container = run.vanilla(6, 9);
        for(var item : List.of(Items.sand, Items.graphite, Items.titanium, Items.thorium)){
            assertTrue(container.items.get(item) > 0, "the disassembler gave " + item.name);
        }
        assertTrue(separator.items.total() >= 10, "the separator filled up once its container went");
    }

    /**
     * An incinerator on a belt of copper and a melter's slag, and a slag incinerator on a belt of copper and another
     * melter's slag. The incinerator's power goes for a while, so it cools and the belt backs up; the slag incinerator's
     * melter stops for a while.
     */
    @Test
    void incineratorsBurnWhatTheyAreGiven(){
        var run = new CraftRun(16, 12);
        run.place("item-source", 2, 3).source(2, 3, "copper").line("conveyor", 3, 3, 0, 3).place("incinerator", 6, 3).place("power-source", 6, 2);
        run.place("melter", 7, 3).place("item-source", 8, 3).source(8, 3, "scrap").place("power-source", 7, 4);

        run.place("item-source", 3, 7).source(3, 7, "copper").line("conveyor", 4, 7, 0, 2).place("slag-incinerator", 6, 7);
        run.place("melter", 7, 7).place("item-source", 8, 7).source(8, 7, "scrap").place("power-source", 7, 8);

        run.at(200, () -> run.enabled(6, 2, false));
        run.at(260, () -> run.enabled(6, 2, true));
        run.at(300, () -> run.enabled(7, 7, false));
        run.at(400, () -> run.enabled(7, 7, true));
        run.run(600);
        assertTrue(((Incinerator.IncineratorBuild)run.vanilla(6, 3)).heat > 0.5f, "hot again");
    }

    /**
     * Attribute crafters: a silicon crucible on hot rock and magma rock (more and faster silicon), a cultivator on moss
     * and spore moss (with the spore environment, which it requires), and a vent condenser on a vent. The rules' heat
     * attribute changes for a while.
     */
    @Test
    void attributeCraftersFollowTheirFloors(){
        var run = new CraftRun(20, 18);
        run.rules(r -> r.env |= mindustry.world.meta.Env.spores, r -> r.env |= 8);
        for(int x = 2; x <= 4; x++) run.floor(x, 2, "magmarock");
        run.floor(2, 3, "hotrock");
        run.floor(3, 4, "hotrock");
        run.place("item-source", 1, 2).source(1, 2, "coal").place("item-source", 1, 3).source(1, 3, "sand")
            .place("item-source", 1, 4).source(1, 4, "pyratite");
        run.place("silicon-crucible", 3, 3).place("power-source", 3, 1).place("container", 5, 3);

        run.floor(10, 3, "moss").floor(11, 3, "spore-moss").floor(11, 4, "spore-moss");
        run.place("cultivator", 10, 3).place("power-source", 10, 2).place("container", 12, 3);
        run.place("liquid-source", 9, 3).liquidSource(9, 3, "water");

        for(int x = 3; x <= 5; x++) for(int y = 9; y <= 11; y++) run.floor(x, y, "rhyolite-vent");
        run.place("vent-condenser", 4, 10).place("power-source", 4, 8).place("conduit", 6, 10, 0).place("liquid-container", 7, 10);

        run.at(200, () -> run.rules(r -> r.attributes.set(mindustry.world.meta.Attribute.heat, 0.5f),
            r -> r.attributes.set(com.xpdustry.shard.sim.rules.Attributes.id("heat"), 0.5f)));
        run.at(400, () -> run.rules(r -> r.attributes.set(mindustry.world.meta.Attribute.heat, 0f),
            r -> r.attributes.set(com.xpdustry.shard.sim.rules.Attributes.id("heat"), 0f)));
        run.run(600);

        assertTrue(((AttributeCrafter.AttributeCrafterBuild)run.vanilla(3, 3)).attrsum > 2f, "the crucible stands on heat");
        assertTrue(run.vanilla(5, 3).items.get(Items.silicon) > 0, "the crucible made silicon");
        assertTrue(run.vanilla(12, 3).items.get(Items.sporePod) > 0, "the cultivator grew spores");
        assertTrue(run.vanilla(7, 10).liquids.get(Liquids.water) > 1f, "the condenser's water went down the conduit");
    }

    /**
     * {@code ignoreLiquidFullness}, which no vanilla block sets: an electrolyzer with it, its outputs going nowhere,
     * keeps progressing once both are full (about frame 750), at its consumers' rate rather than its room's.
     */
    @Test
    void ignoringLiquidFullness(){
        var crafter = (GenericCrafter)Blocks.electrolyzer;
        boolean was = crafter.ignoreLiquidFullness;
        crafter.ignoreLiquidFullness = true;
        try{
            var run = electrolyzer("ignoreLiquidFullness: true");
            run.run(1000);
            var vanilla = run.vanilla(3, 3);
            assertEquals(Blocks.electrolyzer.liquidCapacity, vanilla.liquids.get(Liquids.ozone), 0.01f, "ozone full");
            assertEquals(Blocks.electrolyzer.liquidCapacity, vanilla.liquids.get(Liquids.hydrogen), 0.01f, "hydrogen full");
            assertTrue(vanilla.efficiency > 0f, "still working when full");
        }finally{
            crafter.ignoreLiquidFullness = was;
        }
    }

    /** {@code dumpExtraLiquid = false}, which no vanilla block sets: an electrolyzer stops once either output is full. */
    @Test
    void notDumpingExtraLiquid(){
        var crafter = (GenericCrafter)Blocks.electrolyzer;
        boolean was = crafter.dumpExtraLiquid;
        crafter.dumpExtraLiquid = false;
        try{
            var run = electrolyzer("dumpExtraLiquid: false");
            run.run(1000);
            assertEquals(0f, run.vanilla(3, 3).efficiency, "stopped with one output full");
        }finally{
            crafter.dumpExtraLiquid = was;
        }
    }

    /** A powered electrolyzer on a water source, in content where its definition has {@code field} (vanilla's set alike). */
    static CraftRun electrolyzer(String field){
        Epoch epoch = Epoch.load(Pack.vanilla().with(List.of(new Pack.Source("fixture.yml", """
            - type: block
              id: electrolyzer
              overlay: true
              %s
            """.formatted(field)))));
        var run = new CraftRun(10, 8, epoch);
        // the loader reads vanilla's content, not the fixture's
        run.afterLoad = 0;
        run.place("electrolyzer", 3, 3).place("power-source", 1, 3).place("liquid-source", 1, 2).liquidSource(1, 2, "water");
        return run;
    }
}
