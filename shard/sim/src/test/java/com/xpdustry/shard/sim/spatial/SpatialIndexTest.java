package com.xpdustry.shard.sim.spatial;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.sim.tile.VanillaWorlds;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The grids answer every query as a brute-force scan of the same members does, with vanilla's predicates: random
 * scenes of mixed types, teams, priorities and positions (some outside the map), queried after the fill, after the
 * units moved a little, after removals, and after the placement expired. {@code nearbyEnemies} walks vanilla's team
 * trees ({@link UnitTree}), which place a unit where it was at the fill: like vanilla's, they can miss a unit that moved
 * out of its node since, so after the move it must find a subset of the scan, never a unit the scan doesn't.
 * {@code SpatialQueryOracleTest} checks the same queries against vanilla's quadtrees, the moved trees included.
 */
class SpatialIndexTest{
    static final List<String> TYPES = List.of("dagger", "flare", "atrax", "mace", "evoke", "quell-missile", "risso", "mega",
        "fortress", "horizon");

    @Test
    void queriesMatchABruteForceScan(){
        for(long seed = 0; seed < 40; seed++){
            var scene = new Scene(seed);
            scene.checkAll("filled");
            scene.jiggle(UnitGrid.SLACK * 0.9f);
            scene.checkAll("moved");
            scene.removeSome();
            scene.checkAll("removed");
            scene.index.expire();
            scene.checkAll("expired");
        }
    }

    @Test
    void unitsOutsideTheQuadBoundsStayOutOfTeamGridsOnly(){
        World world = UnitWorlds.world(16, 16, 1);
        Unit far = Unit.spawn(world, VanillaWorlds.VANILLA.unit("dagger"), 1, -400f, 30f, 0f);
        SpatialIndex index = world.part(SpatialIndex.class);
        index.fillTeams(world.units());
        index.fillUnits(world.units());
        var found = new ArrayList<Unit>();
        index.nearby(1, -500f, 0f, 200f, 100f, found::add);
        assertEquals(List.of(), found, "QuadTree.insert drops it");
        index.intersect(-500f, 0f, 200f, 100f, found::add);
        assertEquals(List.of(far), found, "QuadTree.fill keeps it");
    }

    @Test
    void targetPriorityBeatsDistance(){
        World world = UnitWorlds.world(32, 32, 2);
        Unit missile = Unit.spawn(world, VanillaWorlds.VANILLA.unit("quell-missile"), 2, 105f, 100f, 0f);
        Unit dagger = Unit.spawn(world, VanillaWorlds.VANILLA.unit("dagger"), 2, 140f, 100f, 0f);
        Unit.spawn(world, VanillaWorlds.VANILLA.unit("evoke"), 2, 101f, 100f, 0f);
        SpatialIndex index = world.part(SpatialIndex.class);
        index.fillTeams(world.units());
        assertSame(dagger, index.closestEnemy(1, 100f, 100f, 80f, UnitFilter.ANY), "priority 0 beats a closer missile at -1");
        assertSame(dagger, index.bestEnemy(1, 100f, 100f, 80f, UnitFilter.ANY, UnitSort.CLOSEST));
        dagger.remove();
        dagger.dead = true;
        assertSame(missile, index.closestEnemy(1, 100f, 100f, 80f, UnitFilter.ANY), "evoke is untargetable");
        assertNull(index.closestEnemy(SpatialIndex.DERELICT, 100f, 100f, 80f, UnitFilter.ANY), "derelict targets nothing");
        assertSame(missile, index.closestTarget(1, 100f, 100f, 80f, BuildingTargets.NONE));
    }

    /** A random scene, its index filled from the world, and a brute-force answer for every query. */
    static final class Scene{
        final World world;
        final SpatialIndex index;
        final SplittableRandom random;
        final List<Unit> teamMembers = new ArrayList<>(), allMembers = new ArrayList<>();
        final float size;

        Scene(long seed){
            world = UnitWorlds.world(64, 64, seed);
            index = world.part(SpatialIndex.class);
            random = new SplittableRandom(seed);
            size = 64 * 8f;
            int count = random.nextInt(1, 300);
            for(int i = 0; i < count; i++){
                Unit unit = Unit.create(world, VanillaWorlds.VANILLA.unit(TYPES.get(random.nextInt(TYPES.size()))), random.nextInt(4));
                // a few beyond the quadtree bounds, and a dense patch so that best-of queries compete
                float x = i % 50 == 0 ? -300f - random.nextFloat() * 100f : i % 3 == 0 ? 200f + random.nextFloat() * 40f
                    : random.nextFloat() * size;
                unit.set(x, i % 3 == 0 ? 200f + random.nextFloat() * 40f : random.nextFloat() * size);
                if(random.nextInt(10) == 0) unit.elevation = 0f;
                unit.add();
            }
            index.fillTeams(world.units());
            index.fillUnits(world.units());
            float bounds = size + CellGrid.MARGIN * 2f;
            for(int i = 0; i < world.units().size(); i++){
                Unit unit = world.units().get(i);
                allMembers.add(unit);
                if(UnitGrid.overlaps(unit, -CellGrid.MARGIN, -CellGrid.MARGIN, bounds, bounds)) teamMembers.add(unit);
            }
        }

        void jiggle(float by){
            for(Unit unit : allMembers){
                unit.x += (random.nextFloat() * 2f - 1f) * by;
                unit.y += (random.nextFloat() * 2f - 1f) * by;
            }
        }

        void removeSome(){
            for(Unit unit : allMembers){
                if(random.nextInt(5) == 0){
                    unit.remove();
                    if(random.nextBoolean()) unit.dead = true;
                }
            }
        }

        void checkAll(String stage){
            for(int q = 0; q < 60; q++){
                boolean dense = q % 4 == 0;
                float x = dense ? 200f + random.nextFloat() * 40f : random.nextFloat() * size;
                float y = dense ? 200f + random.nextFloat() * 40f : random.nextFloat() * size;
                float w = random.nextFloat() * 120f, h = random.nextFloat() * 120f, range = random.nextFloat() * 160f;
                int team = random.nextInt(4);
                String at = stage + " query " + q + " (" + x + ", " + y + ") team " + team;
                UnitFilter odd = unit -> unit.id() % 2 == 1;

                assertEquals(rect(allMembers, x, y, w, h), sorted(v -> index.intersect(x, y, w, h, v)), at + ": intersect");
                // the team trees keep each unit where the fill put it, as nearbyEnemies' do
                same(stage, rect(teamOf(team), x, y, w, h), sorted(v -> index.nearby(team, x, y, w, h, v)), at + ": nearby");
                same(stage, circle(teamOf(team), x, y, range), sorted(v -> index.nearby(team, x, y, range, v)), at + ": nearby r");
                var enemies = rect(enemiesOf(team), x, y, w, h);
                var enemiesR = circle(enemiesOf(team), x, y, range);
                var treeEnemies = sorted(v -> index.nearbyEnemies(team, x, y, w, h, v));
                var treeEnemiesR = sorted(v -> index.nearbyEnemies(team, x, y, range, v));
                if(stage.equals("filled")){
                    assertEquals(enemies, treeEnemies, at + ": enemies");
                    assertEquals(enemiesR, treeEnemiesR, at + ": enemies r");
                }else{
                    // the trees keep each unit where the fill put it
                    assertTrue(enemies.containsAll(treeEnemies), at + ": enemies, no false positive");
                    assertTrue(enemiesR.containsAll(treeEnemiesR), at + ": enemies r, no false positive");
                }
                same(stage, rect(teamMembers, x, y, w, h), sorted(v -> index.nearbyAnyTeam(x, y, w, h, v)), at + ": any team");
                assertEquals(count(x, y, w, h, odd), index.count(x, y, w, h, odd), at + ": count");
                assertEquals(count(x, y, w, h, odd) > 0, index.any(x, y, w, h, odd), at + ": any");
                assertEquals(anyEntities(x, y, w, h, true), index.anyEntities(x, y, w, h, true), at + ": anyEntities ground");
                assertEquals(anyEntities(x, y, w, h, false), index.anyEntities(x, y, w, h, false), at + ": anyEntities air");
                assertEquals(nearEnemy(team, x, y, w, h), index.nearEnemy(team, x, y, w, h, BuildingTargets.NONE), at + ": nearEnemy");
                assertSame(closestEnemy(team, x, y, range, odd), index.closestEnemy(team, x, y, range, odd), at + ": closestEnemy");
                assertSame(closestEnemy(team, x, y, range, UnitFilter.VALID), index.closestTarget(team, x, y, range, BuildingTargets.NONE),
                    at + ": closestTarget");
                UnitSort strongest = (u, ux, uy) -> -u.maxHealth + Mathf.dst2(u.x, u.y, ux, uy) / 6400f;
                assertSame(bestEnemy(team, x, y, range, odd, strongest), index.bestEnemy(team, x, y, range, odd, strongest),
                    at + ": bestEnemy");
                if(stage.equals("filled")) assertSame(closest(team, x, y, range, odd), index.closest(team, x, y, range, odd), at + ": closest");
            }
        }

        /** Equal after the fill; after moves, a tree finds a subset of the scan, never a unit the scan doesn't. */
        static void same(String stage, List<Integer> scan, List<Integer> found, String message){
            if(stage.equals("filled")) assertEquals(scan, found, message);
            else assertTrue(scan.containsAll(found), message + ", no false positive");
        }

        List<Unit> teamOf(int team){
            return teamMembers.stream().filter(u -> u.team == team).toList();
        }

        List<Unit> enemiesOf(int team){
            return teamMembers.stream().filter(u -> u.team != team).toList();
        }

        static List<Integer> rect(List<Unit> units, float x, float y, float w, float h){
            return units.stream().filter(u -> UnitGrid.overlaps(u, x, y, w, h)).map(Unit::id).sorted().toList();
        }

        static List<Integer> circle(List<Unit> units, float x, float y, float r){
            return units.stream().filter(u -> UnitGrid.overlaps(u, x - r, y - r, r * 2f, r * 2f) && u.within(x, y, r + u.hitSize / 2f))
                .map(Unit::id).sorted().toList();
        }

        static List<Integer> sorted(java.util.function.Consumer<UnitVisitor> query){
            var out = new ArrayList<Integer>();
            query.accept(unit -> out.add(unit.id()));
            out.sort(null);
            return out;
        }

        int count(float x, float y, float w, float h, UnitFilter filter){
            return (int)allMembers.stream().filter(u -> UnitGrid.overlaps(u, x, y, w, h) && filter.test(u)).count();
        }

        boolean anyEntities(float x, float y, float w, float h, boolean ground){
            for(Unit unit : allMembers){
                if(!UnitGrid.overlaps(unit, x, y, w, h)) continue;
                if((unit.isGrounded() && !UnitParams.of(unit.type()).allowLegStep()) != ground) continue;
                float s = Math.min(unit.hitSize * 0.66f, 7.8f), left = unit.x - s / 2f, bottom = unit.y - s / 2f;
                if(left < x + w && left + s > x && bottom < y + h && bottom + s > y) return true;
            }
            return false;
        }

        boolean nearEnemy(int team, float x, float y, float w, float h){
            return teamMembers.stream().anyMatch(u -> u.team != team && u.team != 0 && UnitGrid.overlaps(u, x, y, w, h));
        }

        /** {@code Units.closestEnemy}, over the members in grid visit order's stand-in: team, then id. */
        Unit closestEnemy(int team, float x, float y, float range, UnitFilter filter){
            if(team == 0) return null;
            Unit result = null;
            float cdist = 0f, cpriority = -99999f;
            for(Unit e : byTeamThenId(rect(enemiesOf(team), x - range, y - range, range * 2f, range * 2f))){
                if(e.dead || !filter.test(e) || e.team == 0 || !e.targetable(team)) continue;
                float dst2 = e.dst2(x, y) - e.hitSize * e.hitSize, p = e.targetPriority();
                if(dst2 < range * range && (result == null || dst2 < cdist || p > cpriority) && p >= cpriority){
                    result = e;
                    cdist = dst2;
                    cpriority = p;
                }
            }
            return result;
        }

        Unit bestEnemy(int team, float x, float y, float range, UnitFilter filter, UnitSort sort){
            if(team == 0) return null;
            Unit result = null;
            float cdist = 0f, cpriority = -99999f;
            for(Unit e : byTeamThenId(rect(enemiesOf(team), x - range, y - range, range * 2f, range * 2f))){
                if(e.dead || !filter.test(e) || e.team == 0 || !e.within(x, y, range + e.hitSize / 2f) || !e.targetable(team)) continue;
                float cost = sort.cost(e, x, y), p = e.targetPriority();
                if((result == null || cost < cdist || p > cpriority) && p >= cpriority){
                    result = e;
                    cdist = cost;
                    cpriority = p;
                }
            }
            return result;
        }

        Unit closest(int team, float x, float y, float range, UnitFilter filter){
            Unit result = null;
            float cdist = 0f;
            for(Unit e : byTeamThenId(circle(teamOf(team), x, y, range))){
                if(!e.isValid() || !filter.test(e)) continue;
                float dist = e.dst2(x, y);
                if(result == null || dist < cdist){
                    result = e;
                    cdist = dist;
                }
            }
            return result;
        }

        /** Ties are broken by visit order, which differs; random positions make exact ties all but impossible. */
        List<Unit> byTeamThenId(List<Integer> ids){
            return allMembers.stream().filter(u -> ids.contains(u.id()))
                .sorted(Comparator.comparingInt((Unit u) -> u.team).thenComparingInt(Unit::id)).toList();
        }
    }
}
