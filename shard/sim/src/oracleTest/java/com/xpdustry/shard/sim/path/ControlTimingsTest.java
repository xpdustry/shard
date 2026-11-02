package com.xpdustry.shard.sim.path;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import mindustry.world.Tile;
import org.junit.jupiter.api.*;

import java.lang.management.*;
import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * What the control pathfinder costs (WP71), on the boat VM. Not a behaviour gate: numbers for the report.
 * <ul>
 *   <li><b>The budget.</b> Vanilla gives each field 12 ms of wall time per pass; Shard gives it {@link
 *   ControlPathfinder#MAX_STEPS} steps of pops. Vanilla's own {@code updateFields} is timed on the same searches
 *   Shard runs, whose pops Shard counts (the two pop alike: the cost gate shows equal fields), which gives vanilla's
 *   pops per ms on this machine, and the steps that 12 ms buy.</li>
 *   <li><b>A search</b> (what a new request costs the next pass): {@code initializePathRequest} and the field to the
 *   end, on both sides.</li>
 *   <li><b>100 worlds.</b> One bundled map, forked into 100 worlds, each with {@value #UNITS} ground units walking to
 *   random goals out of straight sight for {@value #TICKS} ticks: every {@code pathPosition} call is timed (the per-unit, per-tick query),
 *   and the heap the control pathfinders hold at the end, per world.</li>
 * </ul>
 */
@Tag("slow")
class ControlTimingsTest{
    static final int WARMUP = 400, SAMPLES = 400, WORLDS = 100, UNITS = 16, TICKS = 600;

    @Test
    void budgetAndSearches(){
        var row = new StringBuilder();
        long allNanos = 0, allPops = 0;
        for(String map : List.of("fortress", "serpulo/frontier", "erekir/onset")){
            Vanilla.host(map);
            VanillaControlPath.take();
            VanillaPathfinder.take();
            World shard = ControlOracle.mirror();
            var vanilla = new mindustry.ai.ControlPathfinder();
            ControlPathfinder mine = shard.part(ControlPathfinder.class);
            mine.bindForTests(shard);
            int width = world.width(), size = width * world.height(), team = state.rules.defaultTeam.id;
            var rand = new Rand(map.hashCode());
            long vanillaNanos = 0, pops = 0;
            var ours = new long[SAMPLES];
            var theirs = new long[SAMPLES];
            int n = 0;
            PathService paths = PathWorlds.paths(shard);
            for(int q = 0; q < WARMUP + SAMPLES; q++){
                int start = open(paths, rand, size, team), goal = open(paths, rand, size, team);
                int sx = start % width, sy = start / width, gx = goal % width, gy = goal / width;
                long t0 = System.nanoTime();
                Object request = VanillaControlPath.request(null, team, 0, goal);
                VanillaControlPath.initialize(vanilla, request, team, 0, sx, sy, gx, gy);
                long t1 = System.nanoTime();
                Object field = VanillaControlPath.field(vanilla, goal, 0, team);
                if(field != null) VanillaControlPath.updateField(vanilla, field);
                long t2 = System.nanoTime();
                ControlPathfinder.Request ourRequest = mine.request(team, 0, goal, sx, sy);
                mine.initializePathRequest(ourRequest, team, 0, sx, sy, gx, gy);
                ControlPathfinder.Field ourField = mine.field(goal, 0, team);
                long before = mine.pops;
                if(ourField != null) mine.updateFields(ourField, 0);
                long t3 = System.nanoTime();
                if(q >= WARMUP){
                    vanillaNanos += t2 - t1;
                    pops += mine.pops - before;
                    theirs[n] = t2 - t0;
                    ours[n++] = t3 - t2;
                }
            }
            allNanos += vanillaNanos;
            allPops += pops;
            Arrays.sort(ours);
            Arrays.sort(theirs);
            row.append(String.format("| %s | %dx%d | %d pops in %s | search p50 %s / p99 %s | vanilla p50 %s / p99 %s |%n",
                map, width, world.height(), pops, us(vanillaNanos), us(ours[SAMPLES / 2]), us(ours[SAMPLES * 99 / 100]),
                us(theirs[SAMPLES / 2]), us(theirs[SAMPLES * 99 / 100])));
        }
        double perMs = allPops / (allNanos / 1e6);
        int steps = (int)(12 * perMs / (ControlPathfinder.STEP_INTERVAL + 1));
        System.out.print("| map | size | vanilla's fields | Shard search | vanilla search |\n|---|---|---|---|---|\n" + row);
        System.out.printf("vanilla pops %.0f tiles a ms: 12 ms is %d steps; MAX_STEPS is %d%n", perMs, steps, ControlPathfinder.MAX_STEPS);
        // the constant stays grounded in vanilla's rate, within what a loaded VM does to it
        assertTrue(allPops > 100_000, "too few pops to measure: " + allPops);
        assertTrue(steps > ControlPathfinder.MAX_STEPS / 4 && steps < ControlPathfinder.MAX_STEPS * 4,
            "12 ms of vanilla's pops is " + steps + " steps, MAX_STEPS is " + ControlPathfinder.MAX_STEPS);
    }

    @Test
    void aHundredWorlds(){
        String map = "fortress";
        Vanilla.host(map);
        VanillaControlPath.take();
        VanillaPathfinder.take();
        World base = new World(WorldConfig.of(PathWorlds.VANILLA, world.width(), world.height(), 1)
            .withKinds(KindRegistry.builtIn().withPassthrough()));
        VanillaMaps.load(base);
        var grid = base.tiles();
        for(Tile tile : world.tiles){
            if(tile.build != null && tile.build.tile == tile) base.buildings().at(grid.pos(tile.x, tile.y)).health = tile.build.health;
        }
        ControlOracle.sameSolidity(base);
        PathService paths = PathWorlds.paths(base);
        paths.rules(VanillaPaths.rules());
        paths.invalidateAll();
        base.tick();

        var worlds = new ArrayList<World>();
        worlds.add(base);
        for(int i = 1; i < WORLDS; i++) worlds.add(base.fork());

        int width = grid.width(), size = grid.size();
        var goals = new TreeMap<Integer, float[]>();
        for(int w = 0; w < WORLDS; w++){
            World world = worlds.get(w);
            var rand = new Rand(w);
            for(int i = 0; i < UNITS; i++){
                int start = open(paths, rand, size, 1), goal = open(paths, rand, size, 1);
                // every walk needs the field: no straight line to the goal
                while(paths.lineOfSight(1, PathCost.GROUND, start, goal)) goal = open(paths, rand, size, 1);
                Unit unit = Unit.spawn(world, world.content().unit("dagger"), 1, start % width * 8f, start / width * 8f, 0f);
                unit.controller(new NoAi());
                goals.put(w * 1000 + i, new float[]{goal % width * 8f, goal / width * 8f});
            }
        }
        // the units are in both measures: the difference is what the walk left behind, the paths' state
        long empty = heap();

        var queries = new long[WORLDS * UNITS * TICKS];
        var ticks = new long[WORLDS * TICKS];
        int q = 0, t = 0, moving = 0;
        for(int tick = 0; tick < TICKS; tick++){
            for(int w = 0; w < WORLDS; w++){
                World world = worlds.get(w);
                long t0 = System.nanoTime();
                world.tick();
                ticks[t++] = System.nanoTime() - t0;
                ControlPathfinder control = ControlPathfinder.of(world);
                var units = world.units();
                for(int i = 0; i < units.size(); i++){
                    Unit unit = units.get(i);
                    float[] goal = goals.get(w * 1000 + i);
                    long q0 = System.nanoTime();
                    ControlPathfinder.Result result = control.pathPosition(unit, goal[0], goal[1]);
                    queries[q++] = System.nanoTime() - q0;
                    if(result.move()){
                        // the test steers: half a tile a tick at most
                        float dx = result.x() - unit.x, dy = result.y() - unit.y, len = (float)Math.sqrt(dx * dx + dy * dy);
                        if(len > 0.01f){
                            float step = Math.min(len, 4f);
                            unit.x += dx / len * step;
                            unit.y += dy / len * step;
                            moving++;
                        }
                    }
                }
            }
        }
        long loaded = heap();
        Arrays.sort(queries, 0, q);
        Arrays.sort(ticks, 0, t);
        int fields = 0, requests = 0;
        for(World world : worlds){
            fields += ControlPathfinder.of(world).fields().size();
            requests += ControlPathfinder.of(world).unitRequests().size();
        }
        System.out.printf("| %s | %dx%d | %d worlds × %d units × %d ticks | query p50 %s / p99 %s / max %s | world tick p50 %s / p99 %s | %d B per world | %d fields, %d requests, %d moves |%n",
            map, width, grid.height(), WORLDS, UNITS, TICKS, us(queries[q / 2]), us(queries[q * 99 / 100]), us(queries[q - 1]),
            us(ticks[t / 2]), us(ticks[t * 99 / 100]), (loaded - empty) / WORLDS, fields, requests, moving);
        assertTrue(moving > 0, "nobody moved");
        assertTrue(fields > 0, "no field was built");
    }

    /** A tile the default team's ground units can stand on. */
    static int open(PathService paths, Rand rand, int size, int team){
        while(true){
            int pos = rand.random(size - 1), cost = PathCost.control(PathCost.GROUND, team, paths.packed(pos));
            if(cost != PathCost.IMPASSABLE && cost < PathCost.SOLID_CAP) return pos;
        }
    }

    static long heap(){
        for(int i = 0; i < 4; i++) System.gc();
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    static String us(long nanos){
        return String.format("%.1f µs", nanos / 1000.0);
    }
}
