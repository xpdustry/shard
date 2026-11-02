package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.content.*;

/**
 * The block fields the movement shapes read every tick, as dense arrays by block id: a floor's {@code shallow}
 * ({@code WaterMoveComp}), and the {@code crushFragile}, {@code crushDamageMultiplier} and {@code unitMoveBreakable}
 * that tanks, crawlers and splashing legs act on. Built once per world with its {@link UnitTable}; immutable. A
 * hand-built definition reads as {@code Block}'s and {@code Floor}'s initializers.
 */
public final class ShapeTable{
    private final boolean[] shallow, crushFragile, unitMoveBreakable;
    private final float[] crushDamageMultiplier;

    private ShapeTable(ContentEpoch content){
        int n = content.blocks();
        shallow = new boolean[n];
        crushFragile = new boolean[n];
        unitMoveBreakable = new boolean[n];
        crushDamageMultiplier = new float[n];
        for(int id = 0; id < n; id++){
            BlockDef block = content.block(id);
            crushDamageMultiplier[id] = 1f;
            if(!block.baked()) continue;
            shallow[id] = block instanceof FloorDef floor && floor.shallow();
            crushFragile[id] = block.crushFragile();
            unitMoveBreakable[id] = block.unitMoveBreakable();
            crushDamageMultiplier[id] = block.crushDamageMultiplier();
        }
    }

    public static ShapeTable of(ContentEpoch content){
        return new ShapeTable(content);
    }

    /** {@code Floor.shallow} of floor {@code id}. */
    public boolean shallow(int id){
        return shallow[id];
    }

    /** {@code Block.crushFragile} of block {@code id}. */
    public boolean crushFragile(int id){
        return crushFragile[id];
    }

    /** {@code Block.unitMoveBreakable} of block {@code id}. */
    public boolean unitMoveBreakable(int id){
        return unitMoveBreakable[id];
    }

    /** {@code Block.crushDamageMultiplier} of block {@code id}. */
    public float crushDamageMultiplier(int id){
        return crushDamageMultiplier[id];
    }
}
