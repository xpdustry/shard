package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.combat.*;
import com.xpdustry.shard.sim.spatial.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Something carried as a payload (vanilla {@code Payload}): a unit or a building, out of the world while carried. Units
 * carry them in a {@link PayloadSlot}, payload blocks in a field of their own ({@code sim.blocks.payload}). The
 * methods are vanilla's ({@code Payload}, {@code UnitPayload}, {@code BuildPayload}); drawing and the red overlay a
 * refused unit flashes are left out.
 *
 * <p>A carried entity is in no group and no registry, so the world hashes and copies it through its holder:
 * {@link #hash} hashes its whole state.
 */
public sealed interface Payload{
    /** {@code payloadUnit}, {@code payloadBlock}: the type byte {@code Payload.write} starts with. */
    int UNIT = 0, BLOCK = 1;

    /** A copy for a fork, holding the copy of the carried entity. */
    Payload copy(CopyContext context);

    /** The carried entity's id. */
    int id();

    /** {@code set(x, y, rotation)}: where the holder carries it. */
    void set(float x, float y, float rotation);

    /** {@code size()}: the hitbox size, in world units. */
    float size();

    float x();

    float y();

    /** {@code rotation()}. */
    default float rotation(){
        return 0f;
    }

    /** {@code content()}: the unit type or the block. */
    ContentDef content();

    /** {@code contentEquals(other)}: the same unit type or block. */
    default boolean contentEquals(Payload other){
        return other.getClass() == getClass() && other.content() == content();
    }

    /** {@code update(unitHolder, buildingHolder)}: one update inside a unit or a building; either may be null. */
    default void update(Unit unitHolder, Building buildingHolder){
    }

    /** {@code isDead()}: it died while carried (a reactor that blew up). */
    default boolean isDead(){
        return false;
    }

    /** {@code dump()}: puts it into the world where it is; whether that worked. */
    default boolean dump(){
        return false;
    }

    /** {@code fits(s)}: whether it fits a holder of {@code s} tiles (3 for a payload conveyor). */
    default boolean fits(float s){
        return size() / Footprint.TILE_SIZE <= s;
    }

    /** {@code destroyed()}: its holder was destroyed with it. */
    default void destroyed(){
    }

    /** {@code remove()}: its holder left the world. */
    default void remove(){
    }

    /** Hashes the kind, then the carried entity's whole state. */
    void hash(Hasher hasher);

    /** Vanilla {@code UnitPayload}. */
    record UnitPayload(Unit unit) implements Payload{
        @Override
        public Payload copy(CopyContext context){
            return new UnitPayload(context.map(unit));
        }

        @Override
        public int id(){
            return unit.id();
        }

        @Override
        public void set(float x, float y, float rotation){
            unit.set(x, y);
            unit.rotation = rotation;
        }

        @Override
        public float size(){
            return unit.hitSize;
        }

        @Override
        public float x(){
            return unit.x;
        }

        @Override
        public float y(){
            return unit.y;
        }

        @Override
        public float rotation(){
            return unit.rotation;
        }

        @Override
        public ContentDef content(){
            return unit.type();
        }

        /** {@code type.updatePayload(unit, unitHolder, buildingHolder)}, which no v160.7 type overrides. */
        @Override
        public void update(Unit unitHolder, Building buildingHolder){
        }

        /**
         * UnitPayload.java:104: the unit joins the world where it is, unless its team is at its cap, it would face a
         * tile it can't stand on, or a ground unit of its kind stands in the way. A nudge keeps dumped units from
         * stacking (two draws), and {@code Units.notifyUnitSpawn} sends it at once ({@link CombatEvent.UnitSpawned}).
         */
        @Override
        public boolean dump(){
            World world = unit.world();
            if(!Units.canCreate(world, unit.team, unit.type())) return false;
            // check if unit can be dumped here
            Solidity solid = unit.shape.solidity(unit);
            if(solid != null){
                var v = new Vec2().trns(unit.rotation, 1f);
                int tx = Math.round((unit.x + v.x) / Footprint.TILE_SIZE), ty = Math.round((unit.y + v.y) / Footprint.TILE_SIZE);
                // cannot dump on solid blocks
                if(TileSolidity.of(world).solid(solid, tx, ty)) return false;
            }
            // cannot dump when there's a lot of overlap going on
            SpatialIndex index = SpatialIndex.of(world);
            boolean legStep = unit.params().allowLegStep();
            if(!unit.params().flying() && index != null
                && index.count(unit.x, unit.y, unit.physicSize() * 1.05f, o -> o.isGrounded() && o.params().allowLegStep() == legStep) > 0){
                return false;
            }
            // prevents stacking
            Rand rand = world.rand();
            float vx = Mathf.range(rand, 0.5f), vy = Mathf.range(rand, 0.5f);
            unit.vel.add(vx, vy);
            unit.add();
            unit.unloaded();
            world.publish(new CombatEvent.UnitSpawned(unit.id()));
            return true;
        }

        @Override
        public void hash(Hasher hasher){
            hasher.i(UNIT);
            unit.hashState(hasher);
        }
    }

    /** Vanilla {@code BuildPayload}. */
    record BuildPayload(Building building) implements Payload{
        @Override
        public Payload copy(CopyContext context){
            return new BuildPayload(context.map(building));
        }

        @Override
        public int id(){
            return building.id();
        }

        public BlockDef block(){
            return building.block();
        }

        @Override
        public void set(float x, float y, float rotation){
            building.set(x, y);
        }

        @Override
        public float size(){
            return building.block().size() * Footprint.TILE_SIZE;
        }

        @Override
        public float x(){
            return building.x();
        }

        @Override
        public float y(){
            return building.y();
        }

        @Override
        public ContentDef content(){
            return building.block();
        }

        /**
         * BuildPayload.java:58: a building updates in a payload block always, and in a unit only if its block may and
         * the rules allow it ({@code unitPayloadUpdate}, or the block's {@code alwaysUpdateInUnits}). It moves to
         * {@code emptyTile} first.
         */
        @Override
        public void update(Unit unitHolder, Building buildingHolder){
            BlockDef block = building.block();
            boolean updateInUnits = !block.baked() || block.updateInUnits(), always = block.baked() && block.alwaysUpdateInUnits();
            if(unitHolder != null && (!updateInUnits || (!building.rules().unitPayloadUpdate && !always))) return;
            building.toEmptyTile();
            building.updatePayload(unitHolder, buildingHolder);
        }

        @Override
        public boolean isDead(){
            return building.dead;
        }

        /** {@code destroyed()}: dead, then its explosion and its block's destroy bullet where it is. */
        @Override
        public void destroyed(){
            building.dead = true;
            building.onDestroyed();
            building.afterDestroyed();
        }

        @Override
        public void remove(){
            building.remove();
        }

        /**
         * {@code place(tile, rotation)}: the building takes the tile at (x, y) with its team ({@link Buildings#place}),
         * then hears it was dropped.
         */
        public void place(int x, int y, int rotation, Cause cause){
            building.world().buildings().place(building, x, y, rotation, cause);
            building.dropped();
        }

        @Override
        public void hash(Hasher hasher){
            hasher.i(BLOCK);
            building.hashState(hasher);
        }
    }
}
