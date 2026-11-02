package com.xpdustry.shard.sim.ai.command;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.sim.ai.*;
import com.xpdustry.shard.sim.path.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code UnitGroup} (UnitGroup.java): units of one physics layer that were ordered to the same point
 * together, and the formation they hold around it. Each unit's {@link CommandAi} holds the group and its index; a
 * fork maps the group once for all of them, and a checkpoint stores it once. Vanilla never saves a group: a loaded
 * unit has none.
 *
 * <p>{@link #calculateFormation} computes the offsets at once, where vanilla submits the work to
 * {@code mainExecutor} and the group is {@code valid} once a pool thread finishes it (ARCHITECTURE.md §1, wall-clock
 * and thread timing; the oracle runs vanilla's executor in the calling thread). The work is vanilla's to the
 * operation, so the offsets are bit-equal: circles of each unit's collision radius are pulled toward the centre until
 * they fill 70% of the circle around them (or 20 times), and pushed apart by a physics pass over Arc's
 * {@link FormationTree} once compressed enough, up to {@code min(1 + size^0.65 / 10, 6)} passes and 40 iterations.
 * Then each ground offset is cut short of the first tile a straight walk from the target to it should avoid
 * ({@link #updateRaycast}).
 */
public final class UnitGroup implements Copyable, Checkpointable{
    /** {@code PhysicsProcess.layerFlying}. */
    public static final int LAYER_FLYING = 2;
    /** {@code PhysicsProcess.layers}. */
    public static final int LAYERS = 4;
    /** {@code Vars.unitCollisionRadiusScale}. */
    static final float COLLISION_RADIUS_SCALE = 0.6f;
    private static final float TILESIZE = 8f;
    private static final Unit[] NONE = {};

    /** {@code units}, in the order they were added. */
    public Unit[] units = NONE;
    @Derived("the stored units' count")
    public int size;
    public int collisionLayer;
    /** Offsets from the target, two floats per unit, or null before the formation is computed. */
    public float[] positions, originalPositions;
    public boolean valid;

    public UnitGroup(){
    }

    /** {@code units.add(unit)}. */
    public void add(Unit unit){
        if(size == units.length) units = Arrays.copyOf(units, Math.max(8, size * 2));
        units[size++] = unit;
    }

    /**
     * {@code calculateFormation(dest, collisionLayer)}: each unit's index in the group, then the offsets around
     * {@code (destX, destY)}.
     */
    public void calculateFormation(World world, float destX, float destY, int collisionLayer){
        this.collisionLayer = collisionLayer;
        float cx = 0f, cy = 0f;
        for(int i = 0; i < size; i++){
            cx += units[i].x;
            cy += units[i].y;
        }
        cx /= size;
        cy /= size;
        positions = new float[size * 2];
        // all positions are relative to the centre
        for(int i = 0; i < size; i++){
            Unit unit = units[i];
            positions[i * 2] = unit.x - cx;
            positions[i * 2 + 1] = unit.y - cy;
            ((CommandAi)unit.controller).groupIndex = i;
        }
        compute(world, destX, destY);
    }

    /** What vanilla's executor runs (UnitGroup.java:46-160). */
    private void compute(World world, float destX, float destY){
        // unused space between circles that needs to be reached for compression to end
        float maxSpaceUsage = 0.7f;
        boolean compress = true;
        int compressionIterations = 0, physicsIterations = 0, totalIterations = 0;
        int maxPhysicsIterations = Math.min(1 + (int)(StrictMath.pow(size, 0.65) / 10), 6);

        float[] sizes = new float[size];
        for(int i = 0; i < size; i++) sizes[i] = units[i].hitSize;
        var tree = new FormationTree(0f, 0f, world.tiles().width() * TILESIZE, world.tiles().height() * TILESIZE, positions, sizes);
        var found = new IntSeq();
        var v1 = new Vec2();
        var v2 = new Vec2();

        // squeeze the circles together, then push them apart over a few physics passes
        while(totalIterations++ < 40 && physicsIterations < maxPhysicsIterations){
            float spaceUsed = 0f;
            if(compress){
                compressionIterations++;
                float maxDst = 1f, totalArea = 0f;
                for(int a = 0; a < size; a++){
                    v1.set(positions[a * 2], positions[a * 2 + 1]).lerp(v2.set(0f, 0f), 0.3f);
                    positions[a * 2] = v1.x;
                    positions[a * 2 + 1] = v1.y;
                    float rad = units[a].hitSize * COLLISION_RADIUS_SCALE;
                    maxDst = Math.max(maxDst, v1.dst(0f, 0f) + rad);
                    totalArea += Mathf.PI * rad * rad;
                }
                // total area of the bounding circle
                float boundingArea = Mathf.PI * maxDst * maxDst;
                spaceUsed = totalArea / boundingArea;
                compress = spaceUsed <= maxSpaceUsage && compressionIterations < 20;
            }

            // uncompress units
            if(!compress || spaceUsed > 0.5f){
                physicsIterations++;
                tree.clear();
                for(int a = 0; a < size; a++) tree.insert(a);
                for(int a = 0; a < size; a++){
                    Unit unit = units[a];
                    float x = positions[a * 2], y = positions[a * 2 + 1], radius = unit.hitSize / 2f;
                    found.clear();
                    tree.intersect(x - radius, y - radius, radius * 2f, radius * 2f, found);
                    for(int res = 0; res < found.size; res++){
                        int b = found.items[res];
                        if(a == b) continue;
                        // simulate collision physics
                        float ox = positions[b * 2], oy = positions[b * 2 + 1];
                        Unit other = units[b];
                        float rs = (radius + other.hitSize / 2f) * 1.2f;
                        float dst = Mathf.dst(x, y, ox, oy);
                        if(dst < rs){
                            v2.set(x - ox, y - oy).setLength(rs - dst);
                            float mass1 = unit.hitSize, mass2 = other.hitSize;
                            float ms = mass1 + mass2;
                            float m1 = mass2 / ms, m2 = mass1 / ms;
                            float scl = 1f;
                            positions[a * 2] += v2.x * m1 * scl;
                            positions[a * 2 + 1] += v2.y * m1 * scl;
                            positions[b * 2] -= v2.x * m2 * scl;
                            positions[b * 2 + 1] -= v2.y * m2 * scl;
                        }
                    }
                }
            }
        }

        originalPositions = positions.clone();
        // raycast from the destination to the offset to make sure it's reachable
        for(int a = 0; a < size; a++) updateRaycast(world, a, destX, destY);
        valid = true;
    }

    /**
     * {@code updateRaycast(index, dest)}: unless the group flies, unit {@code index}'s offset from {@code dest} is cut
     * to stop a tile and 4 units short of the first tile its walk from the target should avoid.
     */
    public void updateRaycast(World world, int index, float destX, float destY){
        if(collisionLayer == LAYER_FLYING || originalPositions == null || positions == null) return;
        ControlPathfinder paths = ControlPathfinder.of(world);
        if(paths == null || !paths.active()) return;
        // coordinates in world space
        float x = originalPositions[index * 2] + destX, y = originalPositions[index * 2 + 1] + destY;
        Unit unit = units[index];
        PathCost cost = world.unitTable().ai(unit.type()).pathCost;
        int res = paths.raycastFastAvoid(world, unit.team, cost, toTile(destX), toTile(destY), toTile(x), toTile(y));
        // collision found: the destination is the point right before it
        if(res != 0){
            var v1 = new Vec2(Point2.x(res) * TILESIZE - destX, Point2.y(res) * TILESIZE - destY);
            v1.setLength(Math.max(v1.len() - TILESIZE - 4f, 0));
            positions[index * 2] = v1.x;
            positions[index * 2 + 1] = v1.y;
        }
    }

    /** {@code World.toTile}. */
    public static int toTile(float coord){
        return Math.round(coord / TILESIZE);
    }

    // region persistence

    @Override
    public Copyable blank(){
        return new UnitGroup();
    }

    @Override
    public void copyTo(Copyable into, CopyContext context){
        var out = (UnitGroup)into;
        out.units = new Unit[units.length];
        for(int i = 0; i < size; i++) out.units[i] = context.map(units[i]);
        out.size = size;
        out.collisionLayer = collisionLayer;
        out.positions = positions == null ? null : positions.clone();
        out.originalPositions = originalPositions == null ? null : originalPositions.clone();
        out.valid = valid;
    }

    /** What a command AI feeds to the hash for its group. */
    public void hash(Hasher hasher){
        hasher.i(size).i(collisionLayer).bool(valid);
        for(int i = 0; i < size; i++) hasher.i(units[i].id());
        hashFloats(hasher, positions);
        hashFloats(hasher, originalPositions);
    }

    private static void hashFloats(Hasher hasher, float[] values){
        hasher.i(values == null ? -1 : values.length);
        if(values != null) for(float value : values) hasher.f(value);
    }

    @Override
    public void write(CheckpointOut out){
        out.refs("units", units, size).i("collisionLayer", collisionLayer).floats("positions", positions)
            .floats("originalPositions", originalPositions).bool("valid", valid);
    }

    @Override
    public void read(CheckpointIn in){
        Unit[] read = in.refs("units", Unit.class);
        if(read == null) throw new CheckpointException("a unit group without units");
        units = read;
        size = read.length;
        collisionLayer = in.i("collisionLayer");
        positions = in.floats("positions");
        originalPositions = in.floats("originalPositions");
        valid = in.bool("valid");
    }

    // endregion
}
