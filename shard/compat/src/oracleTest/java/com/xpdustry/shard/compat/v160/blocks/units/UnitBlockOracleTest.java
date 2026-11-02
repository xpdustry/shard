package com.xpdustry.shard.compat.v160.blocks.units;

import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.blocks.units.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static com.xpdustry.shard.compat.v160.blocks.units.UnitBlockRun.*;
import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit factories and reconstructors against vanilla, frame by frame ({@link UnitBlockRun}): every factory and every
 * reconstructor tier, fed by titanium conveyors from item sources and powered (liquids from liquid sources), each
 * factory pushing its units onto a payload conveyor into a chain of reconstructors, the last pushing out onto the
 * ground. The blocks, their payloads, the units, the clock, the random, entity ids and the Calls ({@code unitBlockSpawn},
 * {@code unitSpawn}, {@code tileConfig}, block snapshots) are compared each frame; vanilla saves halfway, both load it
 * and run on; at the end every save chunk is compared.
 *
 * <p>Units build 27.5 times faster and cost a tenth, on both sides, so a tetrative reconstructor finishes inside the run;
 * the multipliers go through {@code unitBuildSpeed} and the consumers' {@code unitCost} hook.
 */
class UnitBlockOracleTest{
    /** No construct time divides by 27.5, so a finished unit leaves a fraction of progress behind ({@code progress %= 1}). */
    static final float SPEED = 27.5f, COST = 0.1f;

    /** Places {@code block} at (x, y) facing east, fed from above. */
    private static void block(UnitBlockRun run, String block, int x, int y, int size, List<String> items, List<String> liquids){
        run.place(block, x, y, 0).feed(x, y, size, items, liquids);
    }

    /**
     * Serpulo: a ground factory's daggers through the additive, multiplicative, exponential and tetrative
     * reconstructors (dagger, mace, fortress, scepter, reign) and out onto the ground; an air
     * factory's flares through an additive one (horizon); on deep water, a naval factory's rissos through another
     * (minke). Vanilla saves at frame 700; both run to 2,400, by when a reign is out.
     */
    @Test
    void serpuloFactoriesFeedEveryReconstructorTier(){
        int width = 64, height = 64;
        var run = new UnitBlockRun(width, height, pos -> pos / width >= 44 ? "deep-water" : "stone", Map.of()).unitRules(SPEED, COST, -1);
        int y = 12;
        block(run, "ground-factory", 6, y, 3, List.of("silicon", "lead"), List.of());
        run.place("payload-conveyor", 9, y, 0);
        block(run, "additive-reconstructor", 12, y, 3, List.of("silicon", "graphite"), List.of());
        run.place("payload-conveyor", 15, y, 0);
        block(run, "multiplicative-reconstructor", 19, y, 5, List.of("silicon", "titanium", "metaglass"), List.of());
        run.place("payload-conveyor", 23, y, 0);
        block(run, "exponential-reconstructor", 28, y, 7, List.of("silicon", "titanium", "plastanium"), List.of("cryofluid"));
        run.place("payload-conveyor", 33, y, 0);
        // a reign is too big for a payload conveyor: it goes straight onto the ground
        block(run, "tetrative-reconstructor", 39, y, 9, List.of("silicon", "plastanium", "surge-alloy", "phase-fabric"), List.of("cryofluid"));

        y = 32;
        block(run, "air-factory", 6, y, 3, List.of("silicon"), List.of());
        run.place("payload-conveyor", 9, y, 0);
        block(run, "additive-reconstructor", 12, y, 3, List.of("silicon", "graphite"), List.of());

        y = 52;
        block(run, "naval-factory", 6, y, 3, List.of("silicon", "metaglass"), List.of());
        run.place("payload-conveyor", 9, y, 0);
        block(run, "additive-reconstructor", 12, y, 3, List.of("silicon", "graphite"), List.of());
        run.saveAt(700).run(2400);

        for(String unit : List.of("dagger", "mace", "fortress", "scepter", "reign", "flare", "horizon", "risso", "minke")){
            assertTrue(run.seen("made " + unit) > 0, unit + " was made: " + run.seen);
        }
        for(String block : List.of("additive-reconstructor", "multiplicative-reconstructor", "exponential-reconstructor", "tetrative-reconstructor")){
            assertTrue(run.seen("upgrading in " + block) > 0, block + " upgraded a unit: " + run.seen);
        }
        assertNotNull(run.vanillaUnit("reign"), "a reign came out of the tetrative reconstructor onto the ground");
        assertTrue(run.seen("Spawned") > 0 && run.seen("UnitSpawned") > 0, "units dumped with their Calls: " + run.seen);
        assertEquals(1, run.seen("loaded"), "through a save");
    }

    /**
     * Erekir: the tank, ship and mech fabricators through their refabricators (hydrogen) into prime refabricators
     * (nitrogen): stell, locus, precept; elude, avert, obviate; merui, cleroi, anthicus. Vanilla saves at frame 600;
     * both run to 1,200.
     */
    @Test
    void erekirFabricatorsFeedEveryRefabricator(){
        int width = 40, height = 60;
        var run = new UnitBlockRun(width, height, pos -> "stone", Map.of()).unitRules(SPEED, COST, -1);
        String[][] rows = {
            {"tank-fabricator", "beryllium", "silicon", "tank-refabricator"},
            {"ship-fabricator", "graphite", "silicon", "ship-refabricator"},
            {"mech-fabricator", "beryllium", "silicon", "mech-refabricator"}};
        for(int i = 0; i < rows.length; i++){
            int y = 8 + i * 18;
            block(run, rows[i][0], 6, y, 3, List.of(rows[i][1], rows[i][2]), List.of());
            run.place("payload-conveyor", 9, y, 0);
            block(run, rows[i][3], 12, y, 3, List.of("silicon", "tungsten"), List.of("hydrogen"));
            run.place("payload-conveyor", 15, y, 0);
            block(run, "prime-refabricator", 19, y, 5, List.of("thorium", "silicon"), List.of("nitrogen"));
        }
        run.saveAt(600).run(1200);

        for(String unit : List.of("stell", "locus", "precept", "elude", "avert", "obviate", "merui", "cleroi", "anthicus")){
            assertTrue(run.seen("made " + unit) > 0, unit + " was made: " + run.seen);
        }
        for(String block : List.of("tank-refabricator", "ship-refabricator", "mech-refabricator", "prime-refabricator")){
            assertTrue(run.seen("upgrading in " + block) > 0, block + " upgraded a unit: " + run.seen);
        }
        assertEquals(1, run.seen("loaded"), "through a save");
    }

    /**
     * The cap: an air factory for a team capped at two units makes two flares, then holds the third, consuming nothing,
     * through vanilla's save; when a flare dies at frame 600 the held one leaves and the factory goes on. Configs on the
     * way, each a {@code tileConfig}: the plan by index (mono) and by unit type (flare), a command, and null; and a rally
     * point, to which the flares fly.
     */
    @Test
    void theCapStopsAFactoryUntilAUnitDies(){
        var run = new UnitBlockRun(30, Map.of()).unitRules(SPEED, COST, 2);
        block(run, "air-factory", 10, 10, 3, List.of("silicon", "lead"), List.of());
        int move = mindustry.ai.UnitCommand.moveCommand.id;
        run.at(5, () -> run.configure(10, 10, 1, 1));
        run.at(12, () -> run.configure(10, 10, mindustry.content.UnitTypes.flare, new ContentRef(com.xpdustry.shard.content.ContentType.UNIT, EPOCH.unit("flare").id())));
        run.at(20, () -> run.configure(10, 10, mindustry.ai.UnitCommand.moveCommand, new ContentRef.Foreign(UnitBuild.UNIT_COMMAND, move)));
        run.at(26, () -> run.configure(10, 10, null, null));
        // a rally point (commandBuilding's onCommand): new flares fly there
        run.at(30, () -> {
            world.build(10, 10).onCommand(new arc.math.geom.Vec2(22 * 8, 20 * 8));
            run.shard.buildings().at(10, 10).onCommand(new com.xpdustry.shard.common.geom.Vec2(22 * 8, 20 * 8));
        });
        int[] heldAtKill = new int[1];
        run.at(600, () -> {
            heldAtKill[0] = run.seen("holding in air-factory");
            run.kill("flare");
        });
        run.saveAt(400).run(900);

        assertTrue(run.seen("made mono") > 0 || run.seen("made flare") > 0, "it built: " + run.seen);
        assertTrue(heldAtKill[0] > 300, "the capped factory held a flare for most of the run: " + heldAtKill[0]);
        assertEquals(2, flares(), "a third flare left once one died");
        for(var unit : mindustry.gen.Groups.unit){
            if(unit.type == mindustry.content.UnitTypes.flare) assertTrue(unit.within(22 * 8, 20 * 8, 6 * 8), "flares went to the rally point: " + unit);
        }
        assertTrue(run.seen("Configured") >= 4, "the configs: " + run.seen);
        assertEquals(1, run.seen("loaded"), "through a save");
    }

    private static int flares(){
        int n = 0;
        for(var unit : mindustry.gen.Groups.unit) if(unit.type == mindustry.content.UnitTypes.flare) n++;
        return n;
    }
}
