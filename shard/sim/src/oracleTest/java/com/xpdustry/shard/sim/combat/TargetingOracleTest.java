package com.xpdustry.shard.sim.combat;

import com.xpdustry.shard.sim.building.Building;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.spatial.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import mindustry.entities.*;
import mindustry.entities.Units;
import mindustry.game.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static com.xpdustry.shard.sim.combat.CombatOracle.VANILLA;
import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The target searches of {@link Targets} against vanilla's {@code Units} and {@code BlockIndexer}: random scenes of
 * buildings of every size on four teams (derelict included), with walls, turrets, cores, conveyors and untargetable
 * mines, some damaged, placed and removed so that each team's quadtree splits and merges, plus units of mixed types.
 * Queries sit on tile centres half of the time, where distance ties are common and the quadtree's order decides.
 * Every query must return the same unit or the same building on both sides.
 */
class TargetingOracleTest{
    static final int SIZE = 96;
    static final List<String> BLOCKS = List.of("copper-wall", "copper-wall-large", "titanium-wall-large", "scrap-wall-huge", "scrap-wall-gigantic",
        "duo", "hail", "lancer", "ripple", "conveyor", "titanium-conveyor", "router", "mender", "battery", "power-node",
        "shock-mine", "solar-panel", "solar-panel-large", "core-shard", "container", "vault", "graphite-press");
    static final List<String> UNITS = List.of("dagger", "flare", "mace", "quell-missile", "horizon", "crawler", "mega", "risso");

    @Test
    void searchesMatchVanilla(){
        com.xpdustry.shard.oracle.Vanilla.boot();
        int unitHits = 0, buildingHits = 0, allyHits = 0, queries = 0;
        for(long seed = 0; seed < 10; seed++){
            try(var oracle = new CombatOracle(SIZE, "stone")){
                World world = oracle.world;
                com.xpdustry.shard.sim.rules.Rules rules = world.part(WorldRules.class).rules;
                boolean capture = seed % 3 == 2;
                state.rules.coreCapture = capture;
                rules.coreCapture = capture;
                var random = new SplittableRandom(seed);
                var occupied = new boolean[SIZE * SIZE];
                var placed = new ArrayList<int[]>();
                // dense clusters first, so the trees split; then a sprinkle across the map
                int count = random.nextInt(150, 400);
                for(int i = 0; i < count; i++){
                    String name = BLOCKS.get(random.nextInt(BLOCKS.size()));
                    int size = VANILLA.block(name).size();
                    boolean dense = i % 2 == 0;
                    int x = dense ? 30 + random.nextInt(24) : 2 + random.nextInt(SIZE - 6);
                    int y = dense ? 30 + random.nextInt(24) : 2 + random.nextInt(SIZE - 6);
                    if(!free(occupied, x, y, size)) continue;
                    int team = random.nextInt(4);
                    world().tile(x, y).setBlock(content.block(name), Team.get(team), 0);
                    world.buildings().setBlock(x, y, VANILLA.block(name), team, 0, Cause.SERVER);
                    mark(occupied, x, y, size, true);
                    placed.add(new int[]{x, y, size});
                    if(random.nextInt(4) == 0){
                        float fraction = 0.2f + random.nextFloat() * 0.7f;
                        var vb = world().build(x, y);
                        vb.health = vb.maxHealth * fraction;
                        Building sb = world.buildings().at(x, y);
                        sb.health = sb.maxHealth * fraction;
                    }
                }
                for(int i = 0; i < 160; i++){
                    String name = UNITS.get(random.nextInt(UNITS.size()));
                    int team = random.nextInt(4);
                    float x = i % 2 == 0 ? 240f + random.nextFloat() * 200f : random.nextFloat() * SIZE * 8f;
                    float y = i % 2 == 0 ? 240f + random.nextFloat() * 200f : random.nextFloat() * SIZE * 8f;
                    content.unit(name).spawn(Team.get(team), x, y);
                    Unit.spawn(world, VANILLA.unit(name), team, x, y, 0f);
                }
                com.xpdustry.shard.oracle.Vanilla.runPosted();

                for(int round = 0; round < 3; round++){
                    state.teams.updateTeamStats();
                    SpatialIndex index = world.part(SpatialIndex.class);
                    index.fillTeams(world.units());
                    var units = new IdentityHashMap<Object, Integer>();
                    var vanillaUnits = mindustry.gen.Groups.unit.copy(new arc.struct.Seq<>());
                    for(int i = 0; i < vanillaUnits.size; i++) units.put(vanillaUnits.get(i), i);
                    for(int i = 0; i < world.units().size(); i++) units.put(world.units().get(i), i);
                    assertEquals(vanillaUnits.size, world.units().size(), "unit count");

                    for(int q = 0; q < 200; q++){
                        queries++;
                        String at = "seed " + seed + " round " + round + " query " + q;
                        int[] hits = check(world, units, random, at);
                        unitHits += hits[0];
                        buildingHits += hits[1];
                        allyHits += hits[2];
                    }
                    // removals shrink the trees, which merges nodes back and reorders their lists
                    for(int i = placed.size() - 1; i >= 0; i--){
                        if(random.nextInt(3) != 0) continue;
                        int[] p = placed.remove(i);
                        world().tile(p[0], p[1]).remove();
                        world.buildings().setBlock(p[0], p[1], VANILLA.block("air"), 0, 0, Cause.SERVER);
                        mark(occupied, p[0], p[1], p[2], false);
                    }
                    var shardUnits = new ArrayList<Unit>();
                    for(int i = 0; i < world.units().size(); i++) shardUnits.add(world.units().get(i));
                    for(int i = 0; i < vanillaUnits.size; i++){
                        if(random.nextInt(4) == 0){
                            vanillaUnits.get(i).remove();
                            shardUnits.get(i).remove();
                        }
                    }
                    com.xpdustry.shard.oracle.Vanilla.runPosted();
                }
            }
        }
        // the gate is not vacuous: units, enemy buildings and damaged allies were all found many times
        assertTrue(unitHits > queries / 5, "too few unit targets: " + unitHits + " of " + queries);
        assertTrue(buildingHits > queries / 10, "too few building targets: " + buildingHits + " of " + queries);
        assertTrue(allyHits > queries / 10, "too few ally tiles: " + allyHits + " of " + queries);
    }

    static int[] check(World world, Map<Object, Integer> units, SplittableRandom random, String at){
        boolean onTile = random.nextBoolean();
        float x = onTile ? (20 + random.nextInt(50)) * 8f : random.nextFloat() * SIZE * 8f;
        float y = onTile ? (20 + random.nextInt(50)) * 8f : random.nextFloat() * SIZE * 8f;
        float range = onTile ? 8f * (1 + random.nextInt(25)) : random.nextFloat() * 260f;
        int team = random.nextInt(4), source = random.nextInt(5) - 1;
        Team vteam = Team.get(team), vsource = source < 0 ? null : Team.get(source);

        var closest = Units.closestTarget(vteam, x, y, range);
        same(units, closest, Targets.closestTarget(world, team, x, y, range), at + ": closestTarget");
        same(units, Units.closestTarget(vteam, x, y, range, u -> !u.type.flying, b -> !b.block.underBullets),
            Targets.closestTarget(world, team, x, y, range, u -> !UnitParams.of(u.type()).flying(),
                b -> !b.block().underBullets()), at + ": closestTarget ground");
        same(units, Units.closestTarget(vteam, x, y, range, vsource, u -> false, b -> true),
            Targets.closestTarget(world, team, x, y, range, source, u -> false, BuildingFilter.ANY), at + ": closestTarget source");
        same(units, Units.bestTarget(vteam, x, y, range, u -> true, b -> b.block.size > 1, UnitSorts.strongest),
            Targets.bestTarget(world, team, x, y, range, UnitFilter.ANY, b -> b.block().size() > 1,
                (u, ux, uy) -> -u.maxHealth + com.xpdustry.shard.common.math.Mathf.dst2(u.x, u.y, ux, uy) / 6400f),
            at + ": bestTarget strongest");
        var enemy = Units.findEnemyTile(vteam, x, y, range, b -> true);
        same(units, enemy, Targets.findEnemyTile(world, team, x, y, range, BuildingFilter.ANY), at + ": findEnemyTile");
        same(units, indexer.findEnemyTile(vteam, x, y, range, b -> b.block.size, b -> !b.damaged(), vsource),
            Targets.findEnemyTile(world, team, x, y, range, b -> b.block().size(), b -> !b.damaged(), source),
            at + ": findEnemyTile by size");
        var ally = Units.findAllyTile(vteam, x, y, range, mindustry.gen.Building::damaged);
        same(units, ally, Targets.findAllyTile(world, team, x, y, range, Building::damaged), at + ": findAllyTile");
        same(units, Units.closestBuilding(vteam, x, y, range, b -> true),
            Targets.closestBuilding(world, team, x, y, range, BuildingFilter.ANY), at + ": closestBuilding");
        return new int[]{closest instanceof mindustry.gen.Unit ? 1 : 0, enemy != null ? 1 : 0, ally != null ? 1 : 0};
    }

    /** Fails unless both found the same unit (by group index) or the same building (by tile), or neither found one. */
    static void same(Map<Object, Integer> units, Object vanilla, Object shard, String at){
        assertEquals(describe(units, vanilla), describe(units, shard), at);
    }

    static String describe(Map<Object, Integer> units, Object target){
        return switch(target){
            case null -> "nothing";
            case mindustry.gen.Building b -> "building " + b.block.name + " at " + b.tileX() + "," + b.tileY();
            case Building b -> "building " + b.block().name() + " at " + b.tileX() + "," + b.tileY();
            default -> "unit " + units.get(target);
        };
    }

    static boolean free(boolean[] occupied, int x, int y, int size){
        int o = (size - 1) / 2;
        for(int dx = 0; dx < size; dx++){
            for(int dy = 0; dy < size; dy++){
                int tx = x - o + dx, ty = y - o + dy;
                if(tx < 1 || ty < 1 || tx >= SIZE - 1 || ty >= SIZE - 1 || occupied[ty * SIZE + tx]) return false;
            }
        }
        return true;
    }

    static void mark(boolean[] occupied, int x, int y, int size, boolean value){
        int o = (size - 1) / 2;
        for(int dx = 0; dx < size; dx++){
            for(int dy = 0; dy < size; dy++) occupied[(y - o + dy) * SIZE + x - o + dx] = value;
        }
    }

    private static mindustry.core.World world(){
        return mindustry.Vars.world;
    }
}
