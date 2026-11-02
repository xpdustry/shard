package com.xpdustry.shard.sim.path;

import com.xpdustry.shard.content.*;

import java.util.*;

/**
 * What the path layer reads from content, one entry per block wire id (floors, overlays and blocks share the id
 * space): flags and a turret range, from the epoch's {@link BlockDef}s. Immutable and shared by every world on the
 * same epoch. Darkness isn't here: {@code legSolid} reads the tile's load-time data byte, as vanilla's does.
 */
public final class PathBlocks{
    /** {@code Block.solid}; on a floor, {@code Floor.solid}. */
    public static final int SOLID = 1;
    /** {@code Floor.isLiquid}. */
    public static final int LIQUID = 1 << 1;
    /** {@code Floor.isDeep()}: {@code drownTime > 0}. */
    public static final int DEEP = 1 << 2;
    /** {@code Floor.damages()}: {@code damageTaken > 0} or a status with {@code damage > 0}. */
    public static final int DAMAGES = 1 << 3;
    /** {@code Block.teamPassable}. */
    public static final int TEAM_PASSABLE = 1 << 4;
    /** {@code instanceof CoreBlock}. */
    public static final int CORE = 1 << 5;
    /** {@code Blocks.spawn}, the overlay of a ground wave spawn. */
    public static final int SPAWN = 1 << 6;
    /** {@code Block.targetable}. */
    public static final int TARGETABLE = 1 << 7;
    /** {@code Blocks.air}. */
    public static final int AIR = 1 << 8;
    /**
     * The first of eight bits, one per flag of vanilla's {@code EnemyCoreField.randomTargets}, in its order: storage,
     * generator, launchPad, factory, repair, battery, reactor, drill.
     */
    public static final int RANDOM_TARGET_SHIFT = 16;
    public static final int RANDOM_TARGET_FLAGS = 8;
    /** {@code EnemyCoreField.randomTargets}' {@code BlockFlag}s, in its order. */
    public static final List<String> RANDOM_TARGETS = List.of("storage", "generator", "launchPad", "factory", "repair", "battery",
        "reactor", "drill");

    private static final KindKey CORE_BLOCK = KindKey.of("core-block"), TURRET = KindKey.of("turret");

    private final int[] flags;
    private final float[] turretRange;

    /**
     * @param flags each block id's flags, as above
     * @param turretRange each block id's turret {@code range} in world units, or 0 for blocks that aren't turrets
     */
    public PathBlocks(int[] flags, float[] turretRange){
        if(flags.length != turretRange.length) throw new IllegalArgumentException("one flag word and one range per block");
        this.flags = flags.clone();
        this.turretRange = turretRange.clone();
    }

    /**
     * The table for {@code content}, or null when its blocks aren't {@linkplain ContentDef#baked() baked}: hand-built
     * test content defines no path properties, and the path service is inactive on it.
     */
    public static PathBlocks of(ContentEpoch content){
        int n = content.blocks();
        int[] flags = new int[n];
        float[] ranges = new float[n];
        for(int id = 0; id < n; id++){
            BlockDef block = content.block(id);
            if(!block.baked()) return null;
            int bits = (block.solid() ? SOLID : 0) | (block.teamPassable() ? TEAM_PASSABLE : 0) | (block.targetable() ? TARGETABLE : 0)
                | (block.isA(CORE_BLOCK) ? CORE : 0) | (id == 0 ? AIR : 0);
            if(block instanceof FloorDef floor){
                bits |= (floor.isLiquid() ? LIQUID : 0) | (floor.isDeep() ? DEEP : 0) | (floor.damages() ? DAMAGES : 0);
            }
            // WaveSpawner reads tile.overlay() == Blocks.spawn
            if(block.name().equals("spawn")) bits |= SPAWN;
            List<String> blockFlags = block.flags();
            for(int i = 0; i < RANDOM_TARGETS.size(); i++){
                if(blockFlags.contains(RANDOM_TARGETS.get(i))) bits |= 1 << (RANDOM_TARGET_SHIFT + i);
            }
            flags[id] = bits;
            // Turret.range, a field of the turret kinds alone
            ranges[id] = block.isA(TURRET) ? block.fields().f("range") : 0f;
        }
        return new PathBlocks(flags, ranges);
    }

    public int size(){
        return flags.length;
    }

    public int flags(int id){
        return flags[id];
    }

    public boolean is(int id, int flag){
        return (flags[id] & flag) != 0;
    }

    public float turretRange(int id){
        return turretRange[id];
    }

    @Override
    public boolean equals(Object other){
        return other instanceof PathBlocks blocks && Arrays.equals(flags, blocks.flags) && Arrays.equals(turretRange, blocks.turretRange);
    }

    @Override
    public int hashCode(){
        return Arrays.hashCode(flags) * 31 + Arrays.hashCode(turretRange);
    }
}
