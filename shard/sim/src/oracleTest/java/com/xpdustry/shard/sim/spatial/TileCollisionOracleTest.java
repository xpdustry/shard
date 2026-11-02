package com.xpdustry.shard.sim.spatial;

import com.xpdustry.shard.common.geom.Vec2;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.tile.VanillaWorlds;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import mindustry.entities.EntityCollisions;
import mindustry.game.*;
import mindustry.world.blocks.environment.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate for {@link TileSolidity#grid}: the map is edited for real ({@code Buildings.setBlock} and {@code setFloor}, as
 * vanilla's {@code Tile.setBlock} and {@code setFloor}), so units collide with the grid's own bitsets, not a stand-in.
 * Every tile's {@code solid}, {@code legsSolid} and {@code waterSolid} equals {@code EntityCollisions}', and units
 * pushed with fixed {@code moveAt} vectors match vanilla bit for bit, every frame: daggers sliding along a wall line and
 * a large wall, a dagger walking into deep water until it drowns, a dagger killed by a wall placed on it, rissos
 * against the shore and a wall in the water, an atrax walking over the walls, and a flare flying over them to the map
 * edge.
 */
class TileCollisionOracleTest{
    static final int SIZE = 40, FRAMES = 420, WALL_FRAME = 60;
    /** The dagger standing where a wall is placed at {@link #WALL_FRAME}. */
    static final int BURIED_X = 30, BURIED_Y = 30;

    @Test
    void unitsCollideWithTheGridAsWithVanillaTiles(){
        Vanilla.emptyWorld(SIZE, SIZE);
        state.rules.disableUnitCap = true;
        state.rules.waves = false;
        World world = UnitWorlds.world(SIZE, SIZE, 5);
        assertFalse(TileSolidity.of(world) instanceof WorldPart, "the grid answers, not a part");

        for(int ty = 4; ty <= 20; ty++) wall(world, "stone-wall", 0, 20, ty);
        wall(world, "copper-wall-large", 1, 25, 28);
        for(int tx = 4; tx <= 15; tx++){
            for(int ty = 24; ty <= 33; ty++) floor(world, tx <= 13 ? "deep-water" : "shallow-water", tx, ty);
        }
        wall(world, "stone-wall", 0, 8, 28);
        sameSolidity(world, "after the edits");

        var vanilla = new ArrayList<mindustry.gen.Unit>();
        var shard = new ArrayList<Unit>();
        Object[][] units = {
            // along the wall line, from the left and from the right
            {"dagger", 17 * 8f, 10 * 8f, 0.5f, 0.15f},
            {"dagger", 21 * 8f + 4f, 14 * 8f, -0.5f, -0.3f},
            // through shallow water into deep water, stopped by the wall in it, drowned
            {"dagger", 17 * 8f, 28 * 8f, -0.5f, 0f},
            // into the large wall
            {"dagger", 23 * 8f, 28.5f * 8f, 0.4f, 0.05f},
            // buried by a wall at WALL_FRAME
            {"dagger", BURIED_X * 8f, BURIED_Y * 8f, 0f, 0f},
            // against the shore and the wall in the water
            {"risso", 6 * 8f, 26 * 8f, 0.6f, 0.1f},
            {"risso", 11 * 8f, 31 * 8f, -0.8f, -0.4f},
            // legs and wings ignore walls
            {"atrax", 18 * 8f, 12 * 8f, 0.5f, 0f},
            {"flare", 18 * 8f, 6 * 8f, 2f, 0.3f},
        };
        for(Object[] spec : units){
            String type = (String)spec[0];
            float x = (float)spec[1], y = (float)spec[2], dx = (float)spec[3], dy = (float)spec[4];
            mindustry.gen.Unit v = content.unit(type).spawn(Team.sharded, x, y);
            v.controller(new VanillaPush(dx, dy));
            vanilla.add(v);
            Unit s = Unit.spawn(world, VanillaWorlds.VANILLA.unit(type), Team.sharded.id, x, y, 0f);
            s.controller(new ShardPush(dx, dy));
            shard.add(s);
        }
        Vanilla.runPosted();

        for(int frame = 0; frame < FRAMES; frame++){
            if(frame == WALL_FRAME){
                wall(world, "stone-wall", 0, BURIED_X, BURIED_Y);
                sameSolidity(world, "after burying");
            }
            Vanilla.frame();
            world.tick();
            for(int i = 0; i < vanilla.size(); i++){
                var v = vanilla.get(i);
                var s = shard.get(i);
                String at = "frame " + frame + " " + v.type.name + " #" + i;
                same(at + " x", v.x, s.x);
                same(at + " y", v.y, s.y);
                same(at + " vel.x", v.vel.x, s.vel.x);
                same(at + " vel.y", v.vel.y, s.vel.y);
                same(at + " drownTime", v.drownTime, s.drownTime);
                same(at + " elevation", v.elevation, s.elevation);
                assertEquals(v.dead, s.dead, at + " dead");
                assertEquals(v.isAdded(), s.isAdded(), at + " added");
            }
        }

        assertFalse(shard.get(2).isAdded(), "the dagger in deep water drowned");
        assertFalse(shard.get(4).isAdded(), "the buried dagger died");
        assertTrue(shard.get(7).x > 21 * 8f, "the atrax walked over the wall line");
        long alive = shard.stream().filter(Unit::isAdded).count();
        assertEquals(7, alive, "only the drowned and the buried dagger died");
    }

    /**
     * {@code EntityCollisions.overlapsTile}, which unit assemblers check their output against: boxes of every size from
     * a point to a tile and a half, all over a map with walls, water and the map edge, for each predicate.
     */
    @Test
    void overlapsTileAsVanilla(){
        Vanilla.emptyWorld(SIZE, SIZE);
        World world = UnitWorlds.world(SIZE, SIZE, 6);
        for(int ty = 4; ty <= 20; ty++) wall(world, "stone-wall", 0, 20, ty);
        wall(world, "copper-wall-large", 1, 25, 28);
        for(int tx = 4; tx <= 15; tx++){
            for(int ty = 24; ty <= 33; ty++) floor(world, tx <= 13 ? "deep-water" : "shallow-water", tx, ty);
        }
        TileSolidity grid = TileSolidity.of(world);
        var random = new SplittableRandom(44);
        var rect = new arc.math.geom.Rect();
        int hits = 0;
        for(int i = 0; i < 20_000; i++){
            float x = -12f + random.nextFloat() * (SIZE * 8f + 24f), y = -12f + random.nextFloat() * (SIZE * 8f + 24f);
            float w = random.nextFloat() * 12f, h = random.nextFloat() * 12f;
            rect.set(x, y, w, h);
            boolean solid = collisions.overlapsTile(rect, EntityCollisions::solid);
            assertEquals(solid, Collisions.overlapsTile(x, y, w, h, grid, Solidity.SOLID), "solid " + rect);
            assertEquals(collisions.overlapsTile(rect, EntityCollisions::legsSolid), Collisions.overlapsTile(x, y, w, h, grid, Solidity.LEGS), "legs " + rect);
            assertEquals(collisions.overlapsTile(rect, EntityCollisions::waterSolid), Collisions.overlapsTile(x, y, w, h, grid, Solidity.WATER), "water " + rect);
            if(solid) hits++;
        }
        assertTrue(hits > 500 && hits < 19_500, "the boxes hit walls and missed them: " + hits);
    }

    static void wall(World world, String block, int team, int x, int y){
        mindustry.Vars.world.tile(x, y).setBlock(content.block(block), Team.get(team), 0);
        world.buildings().setBlock(x, y, VanillaWorlds.VANILLA.block(block), team, 0, Cause.SERVER);
    }

    static void floor(World world, String floor, int x, int y){
        mindustry.Vars.world.tile(x, y).setFloor((Floor)content.block(floor));
        world.buildings().setFloor(x, y, VanillaWorlds.VANILLA.block(floor), Cause.SERVER);
    }

    /** Every predicate on every tile, and on the ring outside the map. */
    static void sameSolidity(World world, String when){
        TileSolidity grid = TileSolidity.of(world);
        for(int x = -1; x <= SIZE; x++){
            for(int y = -1; y <= SIZE; y++){
                String at = when + ", tile " + x + "," + y;
                assertEquals(EntityCollisions.solid(x, y), grid.solid(Solidity.SOLID, x, y), at + ": solid");
                assertEquals(EntityCollisions.legsSolid(x, y), grid.solid(Solidity.LEGS, x, y), at + ": legs");
                assertEquals(EntityCollisions.waterSolid(x, y), grid.solid(Solidity.WATER, x, y), at + ": water");
            }
        }
    }

    static void same(String what, float vanilla, float shard){
        assertEquals(Float.floatToIntBits(vanilla), Float.floatToIntBits(shard), () -> what + ": " + shard + ", vanilla " + vanilla);
    }

    /** Pushes its unit with one vector, every frame. */
    static final class VanillaPush implements mindustry.entities.units.UnitController{
        final arc.math.geom.Vec2 vector;
        mindustry.gen.Unit unit;

        VanillaPush(float dx, float dy){
            vector = new arc.math.geom.Vec2(dx, dy);
        }

        @Override
        public void unit(mindustry.gen.Unit unit){
            this.unit = unit;
        }

        @Override
        public mindustry.gen.Unit unit(){
            return unit;
        }

        @Override
        public void updateUnit(){
            unit.moveAt(vector);
        }
    }

    /** The same push on Shard's API. */
    static final class ShardPush implements UnitController{
        final Vec2 vector;

        ShardPush(float dx, float dy){
            vector = new Vec2(dx, dy);
        }

        @Override
        public void updateUnit(Unit unit){
            unit.moveAt(vector);
        }

        @Override
        public UnitController copy(CopyContext context){
            return this;
        }

        @Override
        public void hash(Hasher hasher){
        }
    }
}
