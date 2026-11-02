package com.xpdustry.shard.sim.spatial;

import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.tile.VanillaWorlds;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import mindustry.content.*;
import mindustry.game.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit pushing against vanilla's {@code PhysicsProcess}, frame by frame, with units that don't steer (a controller
 * that does nothing) on stone with walls, so that only physics and tile collisions move them.
 *
 * <p>Tolerance: bit for bit, every frame, in every scene:
 * <ul>
 *   <li>sparse scenes, where each body overlaps at most one other in its layer (pairs of every layer and movement
 *   shape, naval units against a shore, pairs pushed into walls);</li>
 *   <li>clusters of five bodies per layer, which vanilla's tree keeps in one leaf;</li>
 *   <li>crowds: 60 overlapping ground units of two sizes and teams against a wall line, 30 flares, 40 atraxes and a
 *   knot of fortresses and maces in a walled corner. A Gauss-Seidel pass depends on the order a body's candidates
 *   come in, so this holds because {@link BodyTree} is vanilla's quadtree, node for node (with a grid in its place,
 *   the mean gap was 3.22 world units and the worst unit 21.7 apart). The crowds settle under {@link #SETTLED} of
 *   overlap.</li>
 * </ul>
 */
class PhysicsTraceOracleTest{
    static final int SIZE = 64, SPARSE_FRAMES = 120, CROWD_FRAMES = 300;
    static final double SETTLED = 0.1;

    @Test
    void sparseScenesMatchBitForBit(){
        var scene = new Scene();
        float px = 40f;
        for(String[] pair : new String[][]{{"dagger", "dagger"}, {"flare", "flare"}, {"atrax", "atrax"}, {"dagger", "mace"},
            {"fortress", "dagger"}, {"horizon", "zenith"}, {"elude", "elude"}, {"stell", "dagger"}}){
            scene.spawn(pair[0], 1, px, 60f);
            scene.spawn(pair[1], 2, px + 3.5f, 61.25f);
            px += 50f;
        }
        // naval units on a deep-water patch, one pushed against its shore
        for(int tx = 44; tx < 60; tx++) for(int ty = 44; ty < 60; ty++) scene.water(tx, ty);
        scene.spawn("risso", 1, 52 * 8f, 52 * 8f);
        scene.spawn("risso", 2, 52 * 8f + 4f, 52 * 8f - 1.5f);
        scene.spawn("retusa", 1, 44 * 8f + 3f, 50 * 8f);
        scene.spawn("risso", 1, 44 * 8f + 6f, 50 * 8f + 2f);
        // a flare over a ground pair: other layers never interact
        scene.spawn("dagger", 1, 100f, 200f);
        scene.spawn("dagger", 1, 105f, 200f);
        scene.spawn("flare", 1, 102f, 200f);
        // pairs pushed into a wall line at tile x = 30
        for(int ty = 20; ty < 40; ty++) scene.wall(30, ty);
        scene.spawn("dagger", 1, 30 * 8f - 9f, 200f);
        scene.spawn("dagger", 1, 30 * 8f - 6f, 201f);
        scene.spawn("mace", 1, 30 * 8f + 8f, 260f);
        scene.spawn("mace", 1, 30 * 8f + 12f, 258f);
        scene.start();
        for(int frame = 0; frame < SPARSE_FRAMES; frame++){
            scene.step();
            for(int i = 0; i < scene.vanilla.size(); i++){
                var v = scene.vanilla.get(i);
                var s = scene.shard.get(i);
                String at = "frame " + frame + " unit " + i + " (" + v.type.name + ")";
                assertEquals(Float.floatToIntBits(v.x), Float.floatToIntBits(s.x), at + " x: " + v.x + " vs " + s.x);
                assertEquals(Float.floatToIntBits(v.y), Float.floatToIntBits(s.y), at + " y: " + v.y + " vs " + s.y);
            }
        }
        assertNotEquals(40f, scene.shard.get(0).x, "the first pair never moved");
    }

    @Test
    void clustersMatchBitForBitWhenTheCandidateOrderAgrees(){
        var scene = new Scene();
        // five bodies per layer keep vanilla's tree one leaf (QuadTree.java:114), so it hands out candidates in body
        // order: the simplest case of the candidate order, kept from when a grid stood in for the tree
        float[][] offsets = {{0f, 0f}, {4f, 1f}, {-3f, 3.5f}, {1.5f, -4f}, {-2f, -2.5f}};
        String[] ground = {"dagger", "mace", "dagger", "dagger", "mace"};
        for(int i = 0; i < offsets.length; i++){
            scene.spawn(ground[i], 1 + i % 2, 278f + offsets[i][0], 278f + offsets[i][1]);
            scene.spawn("flare", 1 + i % 2, 278f - offsets[i][1], 278f + offsets[i][0]);
        }
        scene.start();
        assertTrue(scene.initialResidual > 20, "the cluster did not start overlapping: " + scene.initialResidual);
        for(int frame = 0; frame < SPARSE_FRAMES; frame++){
            scene.step();
            for(int i = 0; i < scene.vanilla.size(); i++){
                var v = scene.vanilla.get(i);
                var s = scene.shard.get(i);
                String at = "frame " + frame + " unit " + i + " (" + v.type.name + ")";
                assertEquals(Float.floatToIntBits(v.x), Float.floatToIntBits(s.x), at + " x: " + v.x + " vs " + s.x);
                assertEquals(Float.floatToIntBits(v.y), Float.floatToIntBits(s.y), at + " y: " + v.y + " vs " + s.y);
            }
        }
        assertTrue(scene.residual(false) < 0.5, "the cluster did not separate: " + scene.residual(false));
    }

    @Test
    void crowdsMatchBitForBit(){
        var scene = new Scene();
        var random = new SplittableRandom(29);
        for(int tx = 10; tx < 50; tx++) scene.wall(tx, 10);
        for(int i = 0; i < 60; i++){
            scene.spawn(i % 5 == 0 ? "mace" : "dagger", 1 + i % 2, 200f + random.nextFloat() * 70f, 100f + random.nextFloat() * 70f);
        }
        for(int i = 0; i < 30; i++) scene.spawn("flare", 1, 300f + random.nextFloat() * 40f, 300f + random.nextFloat() * 40f);
        for(int i = 0; i < 40; i++) scene.spawn("atrax", 1 + i % 3, 120f + random.nextFloat() * 60f, 300f + random.nextFloat() * 60f);
        // a knot of large and small mechs in a walled corner
        for(int t = 0; t < 8; t++){
            scene.wall(52, 44 + t);
            scene.wall(52 + t, 44);
        }
        for(int i = 0; i < 24; i++){
            scene.spawn(i % 3 == 0 ? "fortress" : "mace", 1, 53 * 8f + random.nextFloat() * 30f, 45 * 8f + random.nextFloat() * 30f);
        }
        scene.start();
        assertTrue(scene.initialResidual > 1000, "the crowd did not start overlapping: " + scene.initialResidual);
        for(int frame = 0; frame < CROWD_FRAMES; frame++){
            scene.step();
            for(int i = 0; i < scene.vanilla.size(); i++){
                var v = scene.vanilla.get(i);
                var s = scene.shard.get(i);
                String at = "frame " + frame + " unit " + i + " (" + v.type.name + ")";
                assertEquals(Float.floatToIntBits(v.x), Float.floatToIntBits(s.x), at + " x: " + v.x + " vs " + s.x);
                assertEquals(Float.floatToIntBits(v.y), Float.floatToIntBits(s.y), at + " y: " + v.y + " vs " + s.y);
            }
        }
        double vanilla = scene.residual(true), shard = scene.residual(false);
        System.out.printf("crowd: overlap vanilla %.3f shard %.3f (initial %.3f)%n", vanilla, shard, scene.initialResidual);
        assertTrue(shard < SETTLED, "unsettled: " + shard);
    }

    /** The same units in a vanilla stone world and in a Shard world. */
    static final class Scene{
        final List<mindustry.gen.Unit> vanilla = new ArrayList<>();
        final List<Unit> shard = new ArrayList<>();
        final World world;
        double initialResidual;

        Scene(){
            Vanilla.emptyWorld(SIZE, SIZE);
            state.rules.disableUnitCap = true;
            state.rules.waves = false;
            var handlers = new ArrayList<PhaseHandler>();
            for(Phase phase : Phase.values()) handlers.addAll(Systems.builtIn().handlers(phase));
            var parts = new ArrayList<>(Systems.builtIn().parts());
            world = UnitWorlds.world(SIZE, SIZE, 1, Systems.of(handlers, parts).with(OracleSolids.class, OracleSolids::new));
            assertInstanceOf(OracleSolids.class, TileSolidity.of(world));
        }

        void wall(int x, int y){
            mindustry.Vars.world.tile(x, y).setBlock(Blocks.stoneWall, Team.derelict, 0);
            world.part(OracleSolids.class).wall(x, y);
        }

        void water(int x, int y){
            mindustry.Vars.world.tile(x, y).setFloor((mindustry.world.blocks.environment.Floor)Blocks.deepwater);
            world.part(OracleSolids.class).water(x, y);
        }

        void spawn(String type, int team, float x, float y){
            mindustry.gen.Unit unit = content.unit(type).spawn(Team.get(team), x, y);
            unit.controller(new Idle());
            vanilla.add(unit);
            Unit spawned = Unit.spawn(world, VanillaWorlds.VANILLA.unit(type), team, x, y, 0f);
            // the type's AI steers since W2.17; vanilla's is replaced by Idle above
            spawned.controller(new NoAi());
            shard.add(spawned);
        }

        void start(){
            Vanilla.runPosted();
            initialResidual = residual(true);
        }

        void step(){
            Vanilla.frame();
            world.tick();
        }

        /** The overlap left between bodies of one layer, summed over pairs. */
        double residual(boolean ofVanilla){
            double sum = 0;
            for(int i = 0; i < shard.size(); i++){
                for(int j = i + 1; j < shard.size(); j++){
                    Unit a = shard.get(i), b = shard.get(j);
                    if(a.collisionLayer() != b.collisionLayer()) continue;
                    float ax = ofVanilla ? vanilla.get(i).x : a.x, ay = ofVanilla ? vanilla.get(i).y : a.y;
                    float bx = ofVanilla ? vanilla.get(j).x : b.x, by = ofVanilla ? vanilla.get(j).y : b.y;
                    double rs = (a.hitSize + b.hitSize) * 0.6, d = Math.hypot(ax - bx, ay - by);
                    if(d < rs) sum += rs - d;
                }
            }
            return sum;
        }
    }

    /** A controller that never steers. */
    static final class Idle implements mindustry.entities.units.UnitController{
        mindustry.gen.Unit unit;

        @Override
        public void unit(mindustry.gen.Unit unit){
            this.unit = unit;
        }

        @Override
        public mindustry.gen.Unit unit(){
            return unit;
        }
    }
}
