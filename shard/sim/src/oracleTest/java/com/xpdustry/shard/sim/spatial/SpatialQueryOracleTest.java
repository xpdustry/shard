package com.xpdustry.shard.sim.spatial;

import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.tile.VanillaWorlds;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import mindustry.entities.*;
import mindustry.entities.Units;
import mindustry.game.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The unit queries of {@link SpatialIndex} against vanilla's {@code Units} on its quadtrees (the port of proto 29's
 * {@code Check}): random scenes of mixed types and teams, derelict included, with target priorities 0, -1 (missiles)
 * and -2 (untargetable core units), some units beyond the quadtree bounds, queried right after
 * {@code updateTeamStats} and {@code Groups.unit.updatePhysics}, then again after removals. Sets compare as sets;
 * best-of queries must pick the same unit; {@code nearbyEnemies}, {@code nearby(team…)} and {@code nearby(null…)}, which walk each team's own quadtree
 * ({@link UnitTree}), must give the same units in the same order, also after moves of up to 6 world units on each axis
 * since the fill, where a tree that keeps where it put them finds what vanilla's finds (the grids' reach differs there,
 * the package comment's deviation, so the other queries are left out of that round).
 */
class SpatialQueryOracleTest{
    static final List<String> TYPES = List.of("dagger", "flare", "atrax", "mace", "evoke", "quell-missile", "risso", "mega",
        "fortress", "horizon", "crawler", "poly");
    static final int SIZE = 80;

    @Test
    void queriesMatchVanilla(){
        int compared = 0, unitHits = 0;
        for(long seed = 0; seed < 12; seed++){
            Vanilla.emptyWorld(SIZE, SIZE);
            state.rules.disableUnitCap = true;
            World world = UnitWorlds.world(SIZE, SIZE, seed, Systems.builtIn());
            var random = new SplittableRandom(seed);
            var vanilla = new ArrayList<mindustry.gen.Unit>();
            var shard = new ArrayList<Unit>();
            int count = random.nextInt(50, 400);
            for(int i = 0; i < count; i++){
                String name = TYPES.get(random.nextInt(TYPES.size()));
                int team = random.nextInt(4);
                boolean dense = i % 3 == 0;
                float x = i % 40 == 0 ? -280f - random.nextFloat() * 60f : dense ? 300f + random.nextFloat() * 50f : random.nextFloat() * SIZE * 8f;
                float y = dense ? 300f + random.nextFloat() * 50f : random.nextFloat() * SIZE * 8f;
                vanilla.add(content.unit(name).spawn(Team.get(team), x, y));
                shard.add(Unit.spawn(world, VanillaWorlds.VANILLA.unit(name), team, x, y, 0f));
            }
            state.teams.updateTeamStats();
            mindustry.gen.Groups.unit.updatePhysics();
            SpatialIndex index = world.part(SpatialIndex.class);
            index.fillTeams(world.units());
            index.fillUnits(world.units());

            for(int round = 0; round < 3; round++){
                for(int q = 0; q < 150; q++){
                    compared++;
                    unitHits += check(index, vanilla, shard, random, round == 2, "seed " + seed + " round " + round + " query " + q);
                }
                if(round == 1){
                    // moved since the fill: the trees keep where they put them, queries test where they are
                    for(int i = 0; i < count; i++){
                        float dx = random.nextFloat() * 12f - 6f, dy = random.nextFloat() * 12f - 6f;
                        vanilla.get(i).x += dx;
                        vanilla.get(i).y += dy;
                        shard.get(i).x += dx;
                        shard.get(i).y += dy;
                    }
                    continue;
                }
                for(int i = 0; i < count; i++){
                    if(random.nextInt(4) == 0){
                        vanilla.get(i).remove();
                        shard.get(i).remove();
                    }
                }
            }
        }
        assertTrue(unitHits > compared / 4, "too few queries found a unit: " + unitHits + " of " + compared);
    }

    /** One random query of each kind; {@code treeOnly}, only the ones that walk vanilla's trees. 1 if it found a unit. */
    static int check(SpatialIndex index, List<mindustry.gen.Unit> vanilla, List<Unit> shard, SplittableRandom random, boolean treeOnly,
                     String at){
        boolean dense = random.nextInt(3) == 0;
        float x = dense ? 300f + random.nextFloat() * 50f : random.nextFloat() * SIZE * 8f;
        float y = dense ? 300f + random.nextFloat() * 50f : random.nextFloat() * SIZE * 8f;
        float w = random.nextFloat() * 150f, h = random.nextFloat() * 150f, range = random.nextFloat() * 220f;
        int team = random.nextInt(4);
        Team vteam = Team.get(team);
        var map = new IdentityHashMap<Object, Integer>();
        for(int i = 0; i < vanilla.size(); i++){
            map.put(vanilla.get(i), i);
            map.put(shard.get(i), i);
        }
        // in the team trees' own order
        var enemies = list(map, c -> Units.nearbyEnemies(vteam, x, y, w, h, c::add));
        assertEquals(enemies, list(map, c -> index.nearbyEnemies(team, x, y, w, h, c::add)), at + ": enemies, in order");
        assertEquals(list(map, c -> Units.nearbyEnemies(vteam, x, y, range, c::add)),
            list(map, c -> index.nearbyEnemies(team, x, y, range, c::add)), at + ": enemies radius, in order");
        assertEquals(list(map, c -> Units.nearby(vteam, x, y, w, h, c::add)), list(map, c -> index.nearby(team, x, y, w, h, c::add)),
            at + ": nearby team, in order");
        assertEquals(list(map, c -> Units.nearby(vteam, x, y, range, c::add)), list(map, c -> index.nearby(team, x, y, range, c::add)),
            at + ": nearby radius, in order");
        assertEquals(list(map, c -> Units.nearby(null, x, y, w, h, c::add)), list(map, c -> index.nearbyAnyTeam(x, y, w, h, c::add)),
            at + ": nearby any team, in order");
        if(treeOnly) return enemies.isEmpty() ? 0 : 1;

        assertEquals(set(map, c -> Units.nearby(x, y, w, h, c::add)), set(map, c -> index.intersect(x, y, w, h, c::add)), at + ": nearby");
        assertEquals(set(map, c -> Units.nearby(vteam, x, y, w, h, c::add)), set(map, c -> index.nearby(team, x, y, w, h, c::add)),
            at + ": nearby team");
        assertEquals(set(map, c -> Units.nearby(vteam, x, y, range, c::add)), set(map, c -> index.nearby(team, x, y, range, c::add)),
            at + ": nearby radius");
        assertEquals(set(map, c -> Units.nearby(null, x, y, w, h, c::add)), set(map, c -> index.nearbyAnyTeam(x, y, w, h, c::add)),
            at + ": nearby any team");
        assertEquals(Units.count(x, y, w, h, u -> map.get(u) % 2 == 0), index.count(x, y, w, h, u -> map.get(u) % 2 == 0),
            at + ": count");
        assertEquals(Units.any(x, y, w, h, u -> u.type.flying), index.any(x, y, w, h, u -> UnitParams.of(u.type()).flying()), at + ": any");
        assertEquals(Units.anyEntities(x, y, w, h, true), index.anyEntities(x, y, w, h, true), at + ": anyEntities ground");
        assertEquals(Units.anyEntities(x, y, w, h, false), index.anyEntities(x, y, w, h, false), at + ": anyEntities other");
        assertEquals(Units.nearEnemy(vteam, x, y, w, h), index.nearEnemy(team, x, y, w, h, BuildingTargets.NONE), at + ": nearEnemy");

        Integer closest = map.get(Units.closestEnemy(vteam, x, y, range, u -> true));
        assertEquals(closest, map.get(index.closestEnemy(team, x, y, range, UnitFilter.ANY)), at + ": closestEnemy");
        assertEquals(map.get(Units.closestTarget(vteam, x, y, range)), map.get(index.closestTarget(team, x, y, range, BuildingTargets.NONE)),
            at + ": closestTarget");
        assertEquals(map.get(Units.bestEnemy(vteam, x, y, range, u -> true, UnitSorts.closest)),
            map.get(index.bestEnemy(team, x, y, range, UnitFilter.ANY, UnitSort.CLOSEST)), at + ": bestEnemy closest");
        assertEquals(map.get(Units.bestEnemy(vteam, x, y, range, u -> !u.type.flying, UnitSorts.strongest)),
            map.get(index.bestEnemy(team, x, y, range, u -> !UnitParams.of(u.type()).flying(),
                (u, ux, uy) -> -u.maxHealth + arc.math.Mathf.dst2(u.x, u.y, ux, uy) / 6400f)), at + ": bestEnemy strongest");
        assertEquals(map.get(Units.closest(vteam, x, y, range, u -> true)), map.get(index.closest(team, x, y, range, UnitFilter.ANY)),
            at + ": closest");
        assertEquals(map.get(Units.closestOverlap(vteam, x, y, range, u -> true)),
            map.get(index.closestOverlap(team, x, y, range, UnitFilter.ANY)), at + ": closestOverlap");
        return closest == null ? 0 : 1;
    }

    static List<Integer> list(Map<Object, Integer> map, java.util.function.Consumer<List<Object>> query){
        var found = new ArrayList<Object>();
        query.accept(found);
        var out = new ArrayList<Integer>();
        for(Object unit : found) out.add(map.get(unit));
        return out;
    }

    static TreeSet<Integer> set(Map<Object, Integer> map, java.util.function.Consumer<List<Object>> query){
        var found = new ArrayList<Object>();
        query.accept(found);
        var out = new TreeSet<Integer>();
        for(Object unit : found) out.add(map.get(unit));
        return out;
    }
}
