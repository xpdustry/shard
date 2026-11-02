package com.xpdustry.shard.sim.blocks.defense;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code RegenProjector.mendMap} and {@code lastUpdateFrame} (RegenProjector.java:23-24): the heal every
 * regen projector queued for a building this frame, the largest one winning, which the first projector to update in
 * a frame applies. Projectors that update after it queue for the next frame, so the map is state across ticks.
 *
 * <p>The map is Arc's {@code IntFloatMap} (linear probing on Fibonacci hashing, key 0 kept apart, growing at 0.8 of
 * its table), ported here with its table, because its iteration order is the order buildings are healed in and
 * {@code clear()} keeps the table it grew to. Vanilla's statics belong to the JVM; Shard keeps one per world, which is
 * what a vanilla server hosting that world alone has (ARCHITECTURE §1).
 */
@Register
public final class RegenMends implements WorldPart, Checkpointable{
    private static final float LOAD_FACTOR = 0.8f;

    /** {@code state.updateId} at the last apply, as {@link World#ticks()}; -1 before the first. */
    public long lastUpdateFrame = -1;
    private int[] keyTable = new int[64];
    private float[] valueTable = new float[64];
    private float zeroValue;
    private boolean hasZeroValue;
    private int size;

    /** The world's map, or null in a world built without it. */
    public static RegenMends of(World world){
        return world.find(RegenMends.class);
    }

    private int place(int item){
        return (int)(item * 0x9E3779B97F4A7C15L >>> Long.numberOfLeadingZeros(keyTable.length - 1));
    }

    private int locateKey(int key){
        int mask = keyTable.length - 1;
        for(int i = place(key); ; i = i + 1 & mask){
            int other = keyTable[i];
            // empty space is available
            if(other == 0) return -(i + 1);
            if(other == key) return i;
        }
    }

    /** {@code get(key)}: 0 when absent. */
    public float get(int key){
        if(key == 0) return hasZeroValue ? zeroValue : 0f;
        int i = locateKey(key);
        return i >= 0 ? valueTable[i] : 0f;
    }

    /** {@code put(key, value)}. */
    public void put(int key, float value){
        if(key == 0){
            zeroValue = value;
            if(!hasZeroValue){
                hasZeroValue = true;
                size++;
            }
            return;
        }
        int i = locateKey(key);
        if(i >= 0){
            valueTable[i] = value;
            return;
        }
        i = -(i + 1);
        keyTable[i] = key;
        valueTable[i] = value;
        if(++size >= (int)(keyTable.length * LOAD_FACTOR)) resize(keyTable.length << 1);
    }

    private void resize(int newSize){
        int[] oldKeys = keyTable;
        float[] oldValues = valueTable;
        keyTable = new int[newSize];
        valueTable = new float[newSize];
        if(size > 0){
            int mask = newSize - 1;
            for(int i = 0; i < oldKeys.length; i++){
                int key = oldKeys[i];
                if(key == 0) continue;
                for(int j = place(key); ; j = (j + 1) & mask){
                    if(keyTable[j] == 0){
                        keyTable[j] = key;
                        valueTable[j] = oldValues[i];
                        break;
                    }
                }
            }
        }
    }

    /**
     * The apply at the frame's first regen projector update: {@code for(var entry : mendMap.entries())} heals the
     * building at each position (key 0 first, then table order), then {@code mendMap.clear()}.
     */
    public void apply(World world){
        Buildings buildings = world.buildings();
        if(hasZeroValue) heal(buildings, 0, zeroValue);
        for(int i = 0; i < keyTable.length; i++){
            if(keyTable[i] != 0) heal(buildings, keyTable[i], valueTable[i]);
        }
        // clear(): the table keeps its size
        if(size == 0) return;
        Arrays.fill(keyTable, 0);
        size = 0;
        hasZeroValue = false;
    }

    private static void heal(Buildings buildings, int pos, float amount){
        Building build = buildings.at(com.xpdustry.shard.common.geom.Point2.x(pos), com.xpdustry.shard.common.geom.Point2.y(pos));
        if(build != null){
            build.heal(amount);
            build.recentlyHealed();
        }
    }

    @Override
    public WorldPart copy(CopyContext context){
        var out = new RegenMends();
        out.lastUpdateFrame = lastUpdateFrame;
        out.keyTable = keyTable.clone();
        out.valueTable = valueTable.clone();
        out.zeroValue = zeroValue;
        out.hasZeroValue = hasZeroValue;
        out.size = size;
        return out;
    }

    @Override
    public void hash(Hasher hasher){
        hasher.l(lastUpdateFrame).i(keyTable.length).i(size).bool(hasZeroValue).f(hasZeroValue ? zeroValue : 0f);
        for(int i = 0; i < keyTable.length; i++){
            if(keyTable[i] != 0) hasher.i(i).i(keyTable[i]).f(valueTable[i]);
        }
    }

    @Override
    public void write(CheckpointOut out){
        out.l("lastUpdateFrame", lastUpdateFrame).ints("keyTable", keyTable).floats("valueTable", valueTable).f("zeroValue", zeroValue)
            .bool("hasZeroValue", hasZeroValue).i("size", size);
    }

    @Override
    public void read(CheckpointIn in){
        lastUpdateFrame = in.l("lastUpdateFrame");
        keyTable = in.ints("keyTable");
        valueTable = in.floats("valueTable");
        zeroValue = in.f("zeroValue");
        hasZeroValue = in.bool("hasZeroValue");
        size = in.i("size");
        if(keyTable == null || valueTable == null || keyTable.length != valueTable.length || Integer.bitCount(keyTable.length) != 1){
            throw new CheckpointException("the regen projectors' mend map is corrupt");
        }
    }
}
