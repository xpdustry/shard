package com.xpdustry.shard.compat.v160.blocks.units;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.units.*;
import com.xpdustry.shard.sim.building.ContentRef;
import com.xpdustry.shard.sim.rules.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static com.xpdustry.shard.compat.v160.blocks.units.UnitBlockRun.*;
import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit assemblers, their modules and drones against vanilla, frame by frame ({@link UnitBlockRun}): every building's
 * state (an assembler's progress, warmups, tiers, drones, modules, received payloads and rally point), every unit (a
 * drone's {@code AssemblerAI} spot and tether), the clock, the random, entity ids and the Calls
 * ({@code assemblerDroneSpawned}, {@code assemblerUnitSpawned}, {@code unitSpawn}, {@code unitDespawn}, configs, block
 * snapshots); vanilla saves halfway, both load it and run on; at the end every save chunk is compared.
 *
 * <p>Each assembler faces east, powered by a power source and fed cyanogen by a liquid source under its corner, and
 * payloads by payload conveyors from payload sources behind it and above it. Units build 27.5 times faster and cost a
 * tenth, on both sides, so a payload or two of each kind makes a unit. The rules' planet is Erekir on both sides, so
 * the payload sources may make Erekir walls.
 */
class AssemblerOracleTest{
    static final float SPEED = 27.5f, COST = 0.1f;

    /** An assembler of {@code block} at (x, y) facing east, powered, fed cyanogen, and payloads from two sources. */
    private static void assembler(UnitBlockRun run, String block, int x, int y, String unit, String wall){
        assembler(run, block, x, y, unit, wall, null);
    }

    /**
     * {@link #assembler(UnitBlockRun, String, int, int, String, String)}, and a third source under it whose units
     * ({@code later}) wait on their conveyor until a module raises the assembler's tier.
     */
    private static void assembler(UnitBlockRun run, String block, int x, int y, String unit, String wall, String later){
        if(later != null){
            source(run, x + 1, y - 8, 1, later);
            run.place("payload-conveyor", x + 1, y - 4, 1);
        }
        run.place(block, x, y, 0).feed(x, y, 5, List.of(), List.of("cyanogen"));
        // behind it: a source of units into a payload conveyor
        source(run, x - 8, y, 0, unit);
        run.place("payload-conveyor", x - 4, y, 0);
        // above it: a source of walls into a payload conveyor going down
        source(run, x, y + 8, 3, wall);
        run.place("payload-conveyor", x, y + 4, 3);
    }

    /** A module on the east side of the area of the assembler at (x, y), facing it, powered, fed by a source east of it. */
    private static void module(UnitBlockRun run, int x, int y, String payload){
        run.place("basic-assembler-module", x + 18, y, 2).place("power-source", x + 16, y - 3, 0);
        run.place("payload-conveyor", x + 22, y, 2);
        source(run, x + 26, y, 2, payload);
    }

    private static void source(UnitBlockRun run, int x, int y, int rotation, String content){
        run.place("payload-source", x, y, rotation);
        configure(run, x, y, content);
    }

    /** A payload source's unit type or block, on both sides. */
    private static void configure(UnitBlockRun run, int x, int y, String content){
        if(mindustry.Vars.content.unit(content) != null){
            run.configure(x, y, mindustry.Vars.content.unit(content), new ContentRef(ContentType.UNIT, EPOCH.unit(content).id()));
        }else{
            run.configure(x, y, mindustry.Vars.content.block(content), ContentRef.block(EPOCH.block(content).id()));
        }
    }

    /** The rules' planet is Erekir on both sides: Erekir walls are buildable, so a payload source makes them. */
    private static UnitBlockRun erekir(UnitBlockRun run){
        state.rules.planet = mindustry.content.Planets.erekir;
        run.shard.part(WorldRules.class).rules.planet = 1;
        return run;
    }

    /** Moves the unit of id {@code id} to world (x, y) on both sides, as a client's position would. */
    private static void moveById(UnitBlockRun run, int id, float x, float y){
        mindustry.gen.Groups.unit.getByID(id).set(x, y);
        var ours = (com.xpdustry.shard.sim.unit.Unit)com.xpdustry.shard.sim.entity.EntityGroups.of(run.shard).byId(id);
        ours.set(x, y);
        run.count("moved " + ours.type().name());
    }

    /** The assembler at (x, y) on each side. */
    private static mindustry.world.blocks.units.UnitAssembler.UnitAssemblerBuild vanilla(int x, int y){
        return (mindustry.world.blocks.units.UnitAssembler.UnitAssemblerBuild)world.build(x, y);
    }

    /**
     * The tank, ship and mech assemblers, each fed its first plan's payloads (stell and large tungsten walls, elude and
     * large beryllium walls, merui and large tungsten walls): their drones come one by one, fly to the area's corners, and
     * build a vanquish, quells and a tecta. The ship assembler has a rally point, to which its quells fly; the tank's and
     * mech's units stay on the area and hold it until they are moved off it, 40 frames after they appear. At frame 620
     * each gets a module, fed large carbide walls, which raises its tier (the progress restarts), so the second plan's
     * units (locus, avert, cleroi), waiting on a third conveyor from the start, go in: a conquer, a disrupt and a
     * collaris. The first plan's payloads then wait on their conveyors for good, as in vanilla. Vanilla saves at frame
     * 1,300, with drones up and payloads held; both run on to 3,000.
     */
    @Test
    void tankShipAndMechAssemblersWithModules(){
        int width = 64, height = 68;
        var run = erekir(new UnitBlockRun(width, height, pos -> "stone", Map.of()).unitRules(SPEED, COST, -1));
        String[][] rows = {
            {"tank-assembler", "stell", "tungsten-wall-large", "locus", "vanquish", "conquer"},
            {"ship-assembler", "elude", "beryllium-wall-large", "avert", "quell", "disrupt"},
            {"mech-assembler", "merui", "tungsten-wall-large", "cleroi", "tecta", "collaris"}};
        int x = 11;
        int[] ys = {14, 35, 56};
        for(int i = 0; i < rows.length; i++) assembler(run, rows[i][0], x, ys[i], rows[i][1], rows[i][2], rows[i][3]);
        // the ship assembler's rally point (commandBuilding's onCommand): its quells fly there
        run.at(5, () -> {
            world.build(x, ys[1]).onCommand(new arc.math.geom.Vec2(56 * 8, 35 * 8));
            run.shard.buildings().at(x, ys[1]).onCommand(new com.xpdustry.shard.common.geom.Vec2(56 * 8, 35 * 8));
        });
        // the ground units built hold the area until they are moved off it, 40 frames after they appear
        var first = new TreeMap<String, Integer>();
        int[] moved = {0};
        for(int f = 1; f <= 3000; f++){
            int frame = f;
            run.at(f, () -> {
                for(var unit : mindustry.gen.Groups.unit){
                    first.putIfAbsent(unit.type.name, frame);
                    if(unit.isFlying() || unit.controller() instanceof mindustry.ai.types.AssemblerAI) continue;
                    for(int y : ys){
                        if(unit.within((x + 9) * 8f, y * 8f, 2 * 8f) && frame >= first.get(unit.type.name) + 40){
                            moveById(run, unit.id, (44 + 4 * (moved[0] % 5)) * 8f, (4 + 12 * (moved[0] / 5 % 5)) * 8f);
                            moved[0]++;
                        }
                    }
                }
            });
        }
        run.at(620, () -> {
            for(int i = 0; i < rows.length; i++) module(run, x, ys[i], "carbide-wall-large");
        });
        // the synced blocks' snapshots (assemblers, modules, payload sources and conveyors), every 300 frames
        for(int f = 300; f <= 3000; f += 300){
            int frame = f;
            run.at(f, () -> run.sameBlockSnapshots("frame " + frame));
        }
        run.saveAt(1300).run(3000);

        for(int i = 0; i < rows.length; i++){
            String block = rows[i][0];
            assertTrue(run.seen("drones ready in " + block) > 0, block + "'s drones took their places: " + run.seen);
            assertTrue(run.seen("tier 1 " + block) > 0, block + " linked its module: " + run.seen);
            assertTrue(run.seen("made " + rows[i][4]) > 0, block + " made a " + rows[i][4] + ": " + first + " " + run.seen);
            assertTrue(run.seen("made " + rows[i][5]) > 0, block + " made a " + rows[i][5] + " at tier 1: " + first + " " + run.seen);
            assertTrue(run.seen("occupied " + block) > 0 || i == 1, block + "'s unit held its area: " + run.seen);
        }
        assertTrue(run.seen("linked basic-assembler-module") > 0 && run.seen("holding in basic-assembler-module") > 0,
            "the modules linked and passed payloads on: " + run.seen);
        assertTrue(run.seen("DroneSpawned") >= 12 && run.seen("UnitSpawned") >= 6, "drones and units, with their Calls: " + run.seen);
        int quells = 0;
        for(var unit : mindustry.gen.Groups.unit){
            if(unit.type == mindustry.content.UnitTypes.quell){
                quells++;
                // they overshoot and circle the rally point, as vanilla's do
                assertTrue(unit.within(56 * 8, 35 * 8, 16 * 8) && !unit.within((x + 9) * 8, ys[1] * 8, 20 * 8), "quells went to the rally point: " + unit);
            }
        }
        assertTrue(quells >= 2, "the ship assembler kept building while its quells left: " + quells);
        assertEquals(10, run.seen("block snapshots compared"), run.seen.toString());
        assertTrue(run.seen("synced buildings") > 10 * 3 * 7, "the assemblers, modules and payload blocks were synced: " + run.seen);
        assertEquals(1, run.seen("loaded"), "through a save");
    }

    /**
     * A tank assembler's area held, as vanilla: by a building (a tungsten wall on the area's centre tile, from when its
     * progress passes 0.2, for 60 frames), and by a ground unit (a dagger there, from 0.5, until it is killed 60 frames
     * later), which stop it; a flare there doesn't stop a ground unit. Then the cap: two vanquishes a team; with one
     * built (and moved off), a second is spawned elsewhere while the next is half built, which stops it, with its
     * progress kept, through vanilla's save at 900; killing it at 1,100 lets the assembler finish. At 1,500 the
     * assembler is removed: its drones despawn ({@code BuildingTetherComp}).
     */
    @Test
    void theAreaHeldByABuildingOrAUnitAndTheCapMidBuild(){
        var run = erekir(new UnitBlockRun(48, 40, pos -> "stone", Map.of()).unitRules(SPEED, COST, 2));
        int x = 11, y = 12, sx = x + 9;
        assembler(run, "tank-assembler", x, y, "stell", "tungsten-wall-large");
        int[] state = {0};
        int[] at = new int[8];
        for(int f = 10; f <= 1400; f++){
            int frame = f;
            run.at(f, () -> {
                float progress = vanilla(x, y).progress;
                switch(state[0]){
                    case 0 -> {
                        if(progress > 0.2f){
                            run.place("tungsten-wall", sx, y, 0);
                            run.count("walled");
                            at[0] = frame;
                            state[0]++;
                        }
                    }
                    case 1 -> {
                        if(frame == at[0] + 60){
                            run.remove(sx, y);
                            state[0]++;
                        }
                    }
                    case 2 -> {
                        if(progress > 0.5f){
                            run.spawn("dagger", sx * 8f, y * 8f);
                            run.spawn("flare", sx * 8f + 2f, y * 8f);
                            at[1] = frame;
                            state[0]++;
                        }
                    }
                    case 3 -> {
                        if(frame == at[1] + 60){
                            run.kill("dagger");
                            state[0]++;
                        }
                    }
                    case 4 -> {
                        // the first vanquish is out: move it off the area
                        if(run.vanillaUnit("vanquish") != null){
                            run.move("vanquish", 40 * 8, 4 * 8);
                            state[0]++;
                        }
                    }
                    case 5 -> {
                        if(progress > 0.5f){
                            // a second vanquish elsewhere: the team is at its cap of two mid-build
                            run.spawn("vanquish", 40 * 8, 34 * 8);
                            at[2] = frame;
                            state[0]++;
                        }
                    }
                    case 6 -> {
                        if(frame == 1100){
                            run.kill("vanquish");
                            state[0]++;
                        }
                    }
                    default -> {}
                }
            });
        }
        float[] held = new float[2];
        run.at(1050, () -> held[0] = vanilla(x, y).progress);
        run.at(1090, () -> held[1] = vanilla(x, y).progress);
        run.at(1500, () -> run.remove(x, y));
        run.saveAt(900).run(1700);

        assertTrue(run.seen("walled") == 1 && at[1] > 0, "the wall and the dagger came: " + Arrays.toString(at) + " " + run.seen);
        assertTrue(run.seen("occupied tank-assembler") > 100, "the area was held: " + run.seen);
        assertTrue(at[2] > 0 && at[2] < 900, "the cap was reached mid-build before the save: " + Arrays.toString(at));
        assertTrue(held[0] > 0.5f && held[0] < 1f && held[0] == held[1], "the capped assembler kept its progress and stopped: " + Arrays.toString(held));
        assertTrue(state[0] == 7, "every step ran: " + state[0]);
        assertTrue(run.seen("made vanquish") > 0, run.seen.toString());
        int vanquishes = 0, drones = 0;
        for(var unit : mindustry.gen.Groups.unit){
            if(unit.type == mindustry.content.UnitTypes.vanquish) vanquishes++;
            if(unit.type == mindustry.content.UnitTypes.assemblyDrone) drones++;
        }
        assertEquals(2, vanquishes, "the held vanquish was finished once the cap allowed");
        assertEquals(0, drones, "the drones despawned with their assembler");
        assertTrue(run.seen("Despawn") >= 4, "the drones' despawns were sent: " + run.seen);
        assertEquals(1, run.seen("loaded"), "through a save");
    }
}
