package com.xpdustry.shard.compat.v160.blocks.units;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.ContentRef;
import com.xpdustry.shard.sim.rules.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit assemblers that don't face east, against vanilla frame by frame ({@link UnitBlockRun}, as
 * {@link AssemblerOracleTest}): vanilla turns the area, the unit's spawn, the drones' corners and the module slots
 * with the assembler's rotation ({@code UnitAssembler.getRect}, {@code getUnitSpawn}, {@code moduleFits}).
 * Every building's state, every unit (the drones' {@code AssemblerAI} targets), the clock, the random, entity ids and
 * the Calls are compared after every frame; vanilla saves midway, both load it and run on; every save chunk is compared
 * at the load and at the end.
 *
 * <p>Units build 27.5 times faster and cost a quarter, on both sides (a vanquish takes a stell and three large tungsten
 * walls; {@code Mathf.round}). The rules' planet is Erekir on both sides, as in {@link AssemblerOracleTest}.
 */
class RotatedAssemblerOracleTest{
    static final float SPEED = 27.5f, COST = 0.25f;
    /** Vanilla has one module, of tier 1: a data patch makes it tier 2, which no assembler reaches in steps of one. */
    static final String TIER_TWO = "{\"block\": {\"basic-assembler-module\": {\"tier\": 2}}}";
    /** How far the drones' corners are from the area's centre: {@code areaSize / 2 * sqrt2 * tilesize}, areaSize 13. */
    static final float CORNER = 13 / 2f * arc.math.Mathf.sqrt2 * 8f;

    private static void source(UnitBlockRun run, int x, int y, int rotation, String content){
        run.place("payload-source", x, y, rotation);
        if(mindustry.Vars.content.unit(content) != null){
            run.configure(x, y, mindustry.Vars.content.unit(content), new ContentRef(ContentType.UNIT, UnitBlockRun.EPOCH.unit(content).id()));
        }else{
            run.configure(x, y, mindustry.Vars.content.block(content), ContentRef.block(UnitBlockRun.EPOCH.block(content).id()));
        }
    }

    private static UnitBlockRun erekir(UnitBlockRun run){
        state.rules.planet = mindustry.content.Planets.erekir;
        run.shard.part(WorldRules.class).rules.planet = 1;
        return run;
    }

    /**
     * {@code setrule mapArea}'s {@code Call.setMapArea(x, y, w, h)} on both sides: vanilla's handler
     * ({@code LExecutor.checkMapArea(..., true)}), and Shard's rules set as its {@code WorldHost} sets them, then
     * {@code checkMapArea} with the old area.
     */
    private static void mapArea(UnitBlockRun run, int x, int y, int w, int h){
        mindustry.logic.LExecutor.setMapArea(x, y, w, h);
        var rules = run.shard.part(WorldRules.class).rules;
        int width = run.shard.tiles().width(), height = run.shard.tiles().height();
        x = Math.max(x, 0);
        y = Math.max(y, 0);
        w = Math.min(width, w);
        h = Math.min(height, h);
        int prevX = rules.limitX, prevY = rules.limitY, prevW = rules.limitWidth, prevH = rules.limitHeight;
        if(x == 0 && y == 0 && w == width && h == height){
            assertTrue(rules.limitMapArea, "the fixture only restores an area it limited");
            rules.limitMapArea = false;
        }else{
            if(!rules.limitMapArea){
                prevX = prevY = -1;
                prevW = prevH = 0;
            }
            rules.limitMapArea = true;
            rules.limitX = x;
            rules.limitY = y;
            rules.limitWidth = w;
            rules.limitHeight = h;
        }
        run.shard.buildings().checkMapArea(prevX, prevY, prevW, prevH);
        run.count("map area " + x + "," + y + " " + w + "x" + h);
    }

    /** The assembler at (x, y) on vanilla's side. */
    private static mindustry.world.blocks.units.UnitAssembler.UnitAssemblerBuild vanilla(int x, int y){
        return (mindustry.world.blocks.units.UnitAssembler.UnitAssemblerBuild)world.build(x, y);
    }

    /**
     * Whether the assembler at (x, y) has its four drones on the corners of the area around world (sx, sy), each in
     * position, in order: drone i at {@code i * 90 + 45} degrees, facing the centre.
     */
    private static boolean dronesOnCorners(int x, int y, float sx, float sy){
        var a = vanilla(x, y);
        if(a.units.size != 4) return false;
        for(int i = 0; i < 4; i++){
            var ai = (mindustry.ai.types.AssemblerAI)a.units.get(i).controller();
            float angle = i * 90f + 45f;
            // corner i: right-up, left-up, left-down, right-down of the centre
            float ex = sx + (i == 0 || i == 3 ? 1 : -1) * CORNER * arc.math.Mathf.sqrt2 / 2f, ey = sy + (i < 2 ? 1 : -1) * CORNER * arc.math.Mathf.sqrt2 / 2f;
            if(Math.abs(ai.targetPos.x - ex) > 0.05f || Math.abs(ai.targetPos.y - ey) > 0.05f) return false;
            if(Math.abs(ai.targetAngle - (angle + 180f)) > 0.01f || !ai.inPosition()) return false;
        }
        return true;
    }

    /**
     * A tank assembler facing north, its area above it, fed stell from behind (south), large tungsten walls from the
     * east and locus from the west. Its drones come and take the area's corners; it builds vanquishes at the area's
     * centre, facing north, each moved off the area 40 frames after it appears. At frame 150 two modules join it, one on
     * the area's north side and one on its west side, each facing the area and fed large tungsten walls: they link and
     * pass walls in, but a data patch makes them tier 2, a gap from tier 0 ({@code checkTier}), so the tier stays 0, the
     * progress isn't reset, and the locus (the tier 1 plan's) wait on their conveyor for good. At frame 400 a
     * {@code setMapArea} leaves the assembler outside the map area: {@code allowUpdate()} is false, so its drones are
     * killed and its progress held at 0, through vanilla's save at 500. At frame 650 the whole map is the area again:
     * the assembler is enabled again ({@code autoResetEnabled}), makes new drones, and builds on to 1,100.
     */
    @Test
    void aNorthFacingAssemblerWithATierGapAndItsDronesKilledThroughASave(){
        int width = 40, height = 44, x = 20, y = 12;
        float sx = x * 8f, sy = (y + 9) * 8f;
        var run = erekir(new UnitBlockRun(width, height, pos -> "stone", Map.of(), TIER_TWO).unitRules(SPEED, COST, -1));
        assertEquals(2, ((mindustry.world.blocks.units.UnitAssemblerModule)mindustry.Vars.content.block("basic-assembler-module")).tier, "vanilla's patched tier");
        run.place("tank-assembler", x, y, 1);
        run.place("power-source", x - 3, y - 2, 0);
        run.liquidSource(x - 3, y + 2, "cyanogen");
        source(run, x, y - 8, 1, "stell");
        run.place("payload-conveyor", x, y - 4, 1);
        source(run, x + 8, y, 2, "tungsten-wall-large");
        run.place("payload-conveyor", x + 4, y, 2);
        source(run, x - 8, y, 0, "locus");
        run.place("payload-conveyor", x - 4, y, 0);

        // the modules: north of the area facing down, and west of it facing right; each powered and fed walls
        run.at(150, () -> {
            run.place("basic-assembler-module", x, y + 18, 3).place("power-source", x - 3, y + 16, 0);
            run.place("payload-conveyor", x, y + 22, 3);
            source(run, x, y + 26, 3, "tungsten-wall-large");
            run.place("basic-assembler-module", x - 9, y + 9, 0).place("power-source", x - 11, y + 6, 0);
            run.place("payload-conveyor", x - 13, y + 9, 0);
            source(run, x - 17, y + 9, 0, "tungsten-wall-large");
        });
        int[] gap = new int[3];
        run.at(151, () -> {
            var a = vanilla(x, y);
            gap[0] = a.modules.size;
            gap[1] = a.currentTier;
            gap[2] = a.lastTier;
            var ours = (com.xpdustry.shard.sim.blocks.units.UnitAssembler)run.shard.buildings().at(x, y);
            assertEquals(2, ours.modules.size(), "Shard linked both");
            assertEquals(2, ours.modules.getFirst().tier(), "Shard's patched tier");
        });

        // the vanquishes hold the area until they are moved off it, 40 frames after they appear
        var first = new TreeMap<Integer, Integer>();
        var spawnedAt = new ArrayList<float[]>();
        int[] moved = {0};
        int[] corners = new int[2];
        int[] drones = new int[3];
        int[] vanquishes = new int[2];
        for(int f = 1; f <= 1100; f++){
            int frame = f;
            run.at(f, () -> {
                for(var unit : mindustry.gen.Groups.unit){
                    if(unit.type != mindustry.content.UnitTypes.vanquish) continue;
                    if(first.putIfAbsent(unit.id, frame) == null) spawnedAt.add(new float[]{unit.x, unit.y, unit.rotation});
                    if(unit.within(sx, sy, 2 * 8f) && frame >= first.get(unit.id) + 40){
                        unit.set((31 + 5 * (moved[0] % 2)) * 8f, (17 + 5 * (moved[0] / 2 % 3)) * 8f);
                        ((com.xpdustry.shard.sim.unit.Unit)com.xpdustry.shard.sim.entity.EntityGroups.of(run.shard).byId(unit.id)).set(unit.x, unit.y);
                        moved[0]++;
                    }
                }
                if(dronesOnCorners(x, y, sx, sy)) corners[frame < 400 ? 0 : 1]++;
            });
        }
        run.at(399, () -> {
            drones[0] = vanilla(x, y).units.size;
            vanquishes[0] = first.size();
        });
        run.at(400, () -> mapArea(run, 0, y + 3, width, height - (y + 3)));
        run.at(402, () -> {
            drones[1] = count(mindustry.content.UnitTypes.assemblyDrone);
            assertFalse(vanilla(x, y).allowUpdate(), "the assembler is outside the map area");
        });
        run.at(500, () -> assertEquals(0f, vanilla(x, y).progress, "held at 0 while it may not update"));
        run.at(650, () -> mapArea(run, 0, 0, width, height));
        run.at(1100, () -> {
            drones[2] = count(mindustry.content.UnitTypes.assemblyDrone);
            vanquishes[1] = first.size();
        });
        run.saveAt(500).run(1100);

        assertEquals(List.of(2, 0, 0), List.of(gap[0], gap[1], gap[2]), "two tier 2 modules linked; the tier stays 0 and the progress isn't reset");
        assertTrue(run.seen("linked basic-assembler-module") > 0 && run.seen("holding in basic-assembler-module") > 0, "the modules linked and passed walls on: " + run.seen);
        assertEquals(0, run.seen("tier 1 tank-assembler") + run.seen("tier 2 tank-assembler"), "the gap kept tier 0: " + run.seen);
        assertEquals(0, run.seen("made conquer"), "no tier 1 unit: " + run.seen);
        var locus = (mindustry.world.blocks.payloads.PayloadConveyor.PayloadConveyorBuild)world.build(x - 4, y);
        assertTrue(locus.item instanceof mindustry.world.blocks.payloads.UnitPayload u && u.unit.type == mindustry.content.UnitTypes.locus,
            "the locus waits on its conveyor: " + locus.item);
        assertTrue(corners[0] > 0 && corners[1] > 0, "the drones took the north area's corners, before and after: " + Arrays.toString(corners));
        assertEquals(List.of(4, 0, 4), List.of(drones[0], drones[1], drones[2]), "four drones, killed outside the map area, four again after");
        assertTrue(vanquishes[0] >= 1 && vanquishes[1] > vanquishes[0], "vanquishes before and after the kill: " + Arrays.toString(vanquishes));
        float[] made = spawnedAt.getFirst();
        assertTrue(Math.abs(made[0] - sx) < 8f && Math.abs(made[1] - sy) < 8f && Math.abs(made[2] - 90f) < 5f,
            "the first vanquish came at the north area's centre, facing north: " + Arrays.toString(made));
        assertTrue(run.seen("DroneSpawned") >= 8, "drones before and after the kill, with their Calls: " + run.seen);
        assertEquals(1, run.seen("loaded"), "through a save");
    }

    /**
     * A tank assembler facing west, with a payload void on its area's centre: each vanquish it makes is put into the
     * void ({@code unit.buildOn()}, {@code acceptPayload}, {@code handlePayload}), never added to the world, and the
     * void destroys it. Its drones take the west area's corners. Vanilla saves at 800; both run on to 1,600.
     */
    @Test
    void aWestFacingAssemblerMakesItsUnitsIntoAPayloadVoid(){
        int width = 44, height = 26, x = 30, y = 12;
        float sx = (x - 9) * 8f, sy = y * 8f;
        var run = erekir(new UnitBlockRun(width, height, pos -> "stone", Map.of()).unitRules(SPEED, COST, -1));
        run.place("tank-assembler", x, y, 2).place("power-source", x - 2, y - 3, 0);
        run.liquidSource(x - 1, y - 3, "cyanogen");
        source(run, x + 8, y, 2, "stell");
        run.place("payload-conveyor", x + 4, y, 2);
        source(run, x, y + 8, 3, "tungsten-wall-large");
        run.place("payload-conveyor", x, y + 4, 3);
        run.place("payload-void", x - 9, y, 0);

        var voided = new TreeSet<Integer>();
        int[] corners = {0};
        float[] rotation = {Float.NaN};
        for(int f = 1; f <= 1600; f++){
            run.at(f, () -> {
                var held = ((mindustry.world.blocks.payloads.PayloadVoid.PayloadVoidBuild)world.build(x - 9, y)).payload;
                if(held instanceof mindustry.world.blocks.payloads.UnitPayload u && u.unit.type == mindustry.content.UnitTypes.vanquish){
                    if(voided.add(u.unit.id) && voided.size() == 1) rotation[0] = u.unit.rotation;
                    run.count("vanquish in the void");
                }
                assertEquals(0, count(mindustry.content.UnitTypes.vanquish), "no vanquish is added to the world");
                if(dronesOnCorners(x, y, sx, sy)) corners[0]++;
            });
        }
        run.saveAt(800).run(1600);

        assertTrue(voided.size() >= 2, "vanquishes went into the void: " + voided + " " + run.seen);
        assertEquals(0, run.seen("made vanquish"), "none of them was a unit in the world: " + run.seen);
        assertEquals(180f, rotation[0], 5f, "made facing west");
        assertTrue(corners[0] > 0, "the drones took the west area's corners: " + run.seen);
        assertEquals(1, run.seen("loaded"), "through a save");
    }

    private static int count(mindustry.type.UnitType type){
        int n = 0;
        for(var unit : mindustry.gen.Groups.unit) if(unit.type == type) n++;
        return n;
    }
}
