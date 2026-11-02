package com.xpdustry.shard.sim.path;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;

import java.util.*;
import java.util.stream.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The cost gate of the control pathfinder (WP71): on each bundled Serpulo and Erekir map, hosted by the oracle,
 * {@value #QUERIES} seeded queries per cost type (ground, hover, legs, naval), for the default team and the wave team
 * in turn, run through Shard's {@link ControlPathfinder} and vanilla's, its thread stopped and its fields run to the
 * end. Three quarters of the goals come from a pool of {@value #GOALS} per cost type, so later queries reuse and extend
 * the fields earlier ones built (vanilla's per-goal caches), and every query reuses the clusters earlier ones built
 * (the per-team, per-cost caches). After each query:
 * <ul>
 *   <li>both found a path or neither did, with the same portal nodes;</li>
 *   <li>the goal's field has the same clusters and the same weight on every tile: the path cost from every tile it
 *   covers, the query's start included.</li>
 * </ul>
 * At the end every cluster built on either side has the same portals and the same edges, with bit-equal float costs.
 * Tolerance: none, everything is compared exactly.
 */
class ControlCostsOracleTest{
    static final int QUERIES = 1000, GOALS = 12;

    /** A few maps of each planet for every check. */
    static Stream<String> some(){
        return Stream.of("fortress", "serpulo/groundZero", "serpulo/navalFortress", "erekir/onset", "erekir/lake");
    }

    /** Every bundled Serpulo and Erekir map: the gate. */
    static Stream<String> all(){
        return Stream.concat(ControlOracle.SERPULO.stream(), ControlOracle.EREKIR.stream());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("some")
    void pathCostsEqualVanilla(String map){
        run(map);
    }

    @Tag("slow")
    @ParameterizedTest(name = "{0}")
    @MethodSource("all")
    void pathCostsEqualVanillaOnEveryBundledMap(String map){
        run(map);
    }

    static void run(String map){
        Vanilla.host(map);
        VanillaControlPath.take();
        VanillaPathfinder.take();
        World shard = ControlOracle.mirror();
        ControlOracle.sameTiles(map, shard);
        var vanilla = new mindustry.ai.ControlPathfinder();
        ControlPathfinder mine = shard.part(ControlPathfinder.class);
        mine.bindForTests(shard);

        int width = world.width(), height = world.height(), size = width * height;
        int[] teams = {state.rules.defaultTeam.id, state.rules.waveTeam.id};
        var rand = new Rand(map.hashCode());
        var report = new StringBuilder("| " + map + " | " + width + "x" + height + " |");
        for(int costId = 0; costId < ControlPathfinder.COSTS; costId++){
            int[] goals = new int[GOALS];
            for(int i = 0; i < GOALS; i++) goals[i] = rand.random(size - 1);
            int found = 0, reached = 0;
            long total = 0;
            for(int q = 0; q < QUERIES; q++){
                int team = teams[q & 1], start = rand.random(size - 1), goal = rand.chance(0.75) ? goals[rand.random(GOALS - 1)] : rand.random(size - 1);
                int sx = start % width, sy = start / width, gx = goal % width, gy = goal / width;
                String where = map + " cost " + costId + " query " + q + " team " + team + " (" + sx + "," + sy + ") to (" + gx + "," + gy + ")";

                Object theirs = VanillaControlPath.request(null, team, costId, goal);
                VanillaControlPath.initialize(vanilla, theirs, team, costId, sx, sy, gx, gy);
                ControlPathfinder.Request ours = mine.request(team, costId, goal, sx, sy);
                mine.initializePathRequest(ours, team, costId, sx, sy, gx, gy);

                boolean notFound = VanillaControlPath.notFound(theirs);
                assertEquals(notFound, ours.notFound, where + ": found");
                if(!notFound){
                    found++;
                    assertArrayEquals(VanillaControlPath.resultPath(theirs).toArray(), ours.resultPath.toArray(), where + ": portal path");
                }

                Object field = VanillaControlPath.field(vanilla, goal, costId, team);
                ControlPathfinder.Field ourField = mine.field(goal, costId, team);
                if(field != null) VanillaControlPath.updateField(vanilla, field);
                if(ourField != null) mine.updateFields(ourField, 0);
                ControlOracle.sameField(where, field, ourField);
                if(ourField != null && !notFound){
                    int[] cluster = ourField.fields[sx / ControlPathfinder.CLUSTER_SIZE + (sy / ControlPathfinder.CLUSTER_SIZE) * mine.cwidth()];
                    int cost = cluster == null ? 0 : cluster[sx % ControlPathfinder.CLUSTER_SIZE + (sy % ControlPathfinder.CLUSTER_SIZE) * ControlPathfinder.CLUSTER_SIZE];
                    if(cost > 0){
                        reached++;
                        total += cost;
                    }
                }
            }
            report.append(' ').append(found).append('/').append(reached).append('/').append(total).append(" |");
        }
        int clusters = ControlOracle.sameClusters(map, vanilla, mine);
        assertTrue(clusters > 0, map + ": no clusters were compared");
        report.append(' ').append(clusters).append(" |");
        System.out.println(report);
    }
}
