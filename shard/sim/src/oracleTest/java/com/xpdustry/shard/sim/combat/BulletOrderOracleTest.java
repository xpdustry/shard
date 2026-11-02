package com.xpdustry.shard.sim.combat;

import com.xpdustry.shard.sim.bullet.*;
import com.xpdustry.shard.sim.spatial.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.world.blocks.defense.turrets.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * W6.1: which unit a non-piercing bullet hits when its sweep overlaps two on the same tick, exact against vanilla.
 * Vanilla takes the first candidate {@code EntityCollisions.updateCollision} collects: per enemy team in id order, that
 * team's quadtree in {@code intersect} order (children top left, top right, bottom left, bottom right, then the node's
 * own units). Four idle pairs, each hit by one duo copper bullet fired along the line between its two units:
 * <ul>
 *   <li>crux, across the root's horizontal midline (y 256): the one above, in the top-left child, is found first;</li>
 *   <li>crux, across the vertical midline (x 256): the one on the left, in the bottom-left child;</li>
 *   <li>crux, one straddling the midline, which stays in the root, and one below it: the child's, before the root's
 *   own;</li>
 *   <li>malis above the midline and crux below: crux's tree comes first, whatever the geometry.</li>
 * </ul>
 * Crux has six units here, so its tree splits. In each crux pair the unit the tree finds second joined the fill first,
 * and both sit in one grid cell, so the grid's cell order (fill order within a cell) would pick the other: the test
 * checks that it does, so the hit follows the tree, not the fill. Every frame the units (health, position), the live
 * bullets, the random and the id counter are equal, for {@value #FRAMES} frames.
 */
class BulletOrderOracleTest{
    static final int SIZE = 64, FRAMES = 60;
    static final int CRUX = 2, MALIS = 3;

    /** A unit: team, x, y. Within a pair, the first listed joins the fill first. */
    record Spot(int team, float x, float y){
    }

    /**
     * A pair, its bullet (from, angle), the sweep box across both units the test queries, and which of the two (0 or 1)
     * vanilla's order hits.
     */
    record Pair(String name, Spot first, Spot second, float fromX, float fromY, float angle, float[] box, int hit, boolean sameTeam){
    }

    static final List<Pair> PAIRS = List.of(
        // the bullet's 4-unit box along y 256 overlaps both: the second (above, top left) is found first
        new Pair("across y 256", new Spot(CRUX, 200f, 251f), new Spot(CRUX, 200f, 261f), 170f, 256f, 0f,
            new float[]{190f, 254f, 20f, 4f}, 1, true),
        // along x 256, upwards: the second (left, bottom left) first
        new Pair("across x 256", new Spot(CRUX, 261f, 100f), new Spot(CRUX, 251f, 100f), 256f, 70f, 90f,
            new float[]{254f, 90f, 4f, 20f}, 1, true),
        // the first straddles y 256 and stays in the root; the second is in the bottom-left child, found first
        new Pair("root and child", new Spot(CRUX, 120f, 256f), new Spot(CRUX, 120f, 247.5f), 90f, 251.5f, 0f,
            new float[]{110f, 249.5f, 20f, 4f}, 1, true),
        // malis above (top right), crux below (bottom right): crux, the lower team id, first
        new Pair("two teams", new Spot(MALIS, 380f, 261f), new Spot(CRUX, 380f, 251f), 350f, 256f, 0f,
            new float[]{370f, 254f, 20f, 4f}, 1, false)
    );

    @Test
    void aBulletHitsTheFirstUnitOfVanillasTreeOrder(){
        com.xpdustry.shard.oracle.Vanilla.boot();
        try(var o = new CombatOracle(SIZE, "stone")){
            World world = o.world;
            var vanillaUnits = new ArrayList<mindustry.gen.Unit>();
            var shardUnits = new ArrayList<Unit>();
            for(Pair pair : PAIRS){
                for(Spot spot : List.of(pair.first(), pair.second())){
                    mindustry.gen.Unit v = UnitTypes.dagger.spawn(Team.get(spot.team()), spot.x(), spot.y());
                    v.controller(new Idle.VanillaIdle());
                    vanillaUnits.add(v);
                    Unit s = Unit.spawn(world, CombatOracle.VANILLA.unit("dagger"), spot.team(), spot.x(), spot.y(), 0f);
                    s.controller(new Idle.ShardIdle());
                    shardUnits.add(s);
                }
            }
            var vanillaType = ((ItemTurret)content.block("duo")).ammoTypes.get(Items.copper);
            BulletType shardType = world.bullets().types(world).get(vanillaType.id);
            assertEquals(4f, vanillaType.hitSize, "the boxes above assume the copper bullet's 4-unit hitbox");
            assertFalse(vanillaType.pierce, "a bullet that stops at its first hit");

            // the team trees are filled at the first frame
            o.frame();
            sameUnits(vanillaUnits, shardUnits, "frame 0");
            SpatialIndex index = SpatialIndex.of(world);
            for(int p = 0; p < PAIRS.size(); p++){
                Pair pair = PAIRS.get(p);
                float[] b = pair.box();
                var tree = new ArrayList<Unit>();
                index.nearbyEnemies(Team.sharded.id, b[0], b[1], b[2], b[3], tree::add);
                assertEquals(List.of(shardUnits.get(p * 2 + pair.hit()), shardUnits.get(p * 2 + 1 - pair.hit())), tree,
                    pair.name() + ": both in the sweep, in the tree's order");
                if(pair.sameTeam()){
                    // nearby(team) walks the same tree since W6.8; the grid's cell order (the other first) is gone
                    var team = new ArrayList<Unit>();
                    index.nearby(CRUX, b[0], b[1], b[2], b[3], team::add);
                    assertEquals(tree, team, pair.name() + ": nearby(team) is in the tree's order too");
                }
            }

            for(Pair pair : PAIRS){
                vanillaType.create(null, Team.sharded, pair.fromX(), pair.fromY(), pair.angle());
                shardType.create(world, null, Team.sharded.id, pair.fromX(), pair.fromY(), pair.angle());
            }
            for(int frame = 1; frame < FRAMES; frame++){
                String at = "frame " + frame;
                o.frame();
                o.sameRandom(at);
                o.sameIds(at);
                assertEquals(o.vanillaBullets(), o.shardBullets(), at + ": live bullets");
                sameUnits(vanillaUnits, shardUnits, at);
            }
            assertEquals(0, world.bullets().size(), "every bullet hit");
            for(int p = 0; p < PAIRS.size(); p++){
                Pair pair = PAIRS.get(p);
                Unit hit = shardUnits.get(p * 2 + pair.hit()), spared = shardUnits.get(p * 2 + 1 - pair.hit());
                assertTrue(hit.health < hit.maxHealth, pair.name() + ": the tree's first was hit");
                assertEquals(spared.maxHealth, spared.health, pair.name() + ": the other was spared");
            }
        }
    }

    static void sameUnits(List<mindustry.gen.Unit> vanilla, List<Unit> shard, String at){
        for(int i = 0; i < vanilla.size(); i++){
            var v = vanilla.get(i);
            var s = shard.get(i);
            String u = at + " unit " + i;
            assertEquals(v.isAdded(), s.isAdded(), u + " added");
            CombatOracle.same(u + " health", v.health, s.health);
            CombatOracle.same(u + " x", v.x, s.x);
            CombatOracle.same(u + " y", v.y, s.y);
        }
    }
}
