package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.content.*;

import java.util.*;

/**
 * The {@code UnitType} mining fields that {@code MinerComp} and {@code MinerAI} read, for one unit type, read once from
 * the content, with each item's tier check and mining time worked out. Immutable; the world's {@link UnitTable} keeps
 * one per type. A hand-built type (test content) has a fresh {@code UnitType}'s values, so it can't mine, and its
 * content's items aren't looked up: hand-built content may count items without defining them.
 */
public final class MineType{
    /** {@code mineTier}: -1 for a type that can't mine. */
    public final int tier;
    public final float speed, range;
    public final boolean floor, walls;
    /** {@code mineItems}, as item ids, in vanilla's order: what a miner AI picks from. None for a hand-built type. */
    public final int[] items;
    /** Per item id: {@code canMine(item)}, {@code mineTier >= item.hardness}. */
    private final boolean[] mineable;
    /** Per item id: frames of {@code mineTimer} one item takes, {@code 50 + (mineHardnessScaling ? hardness * 15 : 15)}. */
    private final float[] time;

    MineType(ContentEpoch content, UnitDef type){
        boolean baked = type.baked();
        tier = baked ? type.mineTier() : -1;
        speed = baked ? type.mineSpeed() : 1f;
        range = baked ? type.mineRange() : 70f;
        floor = !baked || type.mineFloor();
        walls = baked && type.mineWalls();
        boolean scaling = !baked || type.mineHardnessScaling();
        mineable = new boolean[content.items()];
        time = new float[content.items()];
        Arrays.fill(time, 65f);
        if(!baked){
            items = new int[0];
            return;
        }
        var ids = new ArrayList<Integer>();
        for(String name : type.mineItems()){
            ItemDef item = content.item(name);
            if(item != null) ids.add(item.id());
        }
        items = ids.stream().mapToInt(Integer::intValue).toArray();
        for(int id = 0; id < mineable.length; id++){
            ItemDef item = content.item(id);
            int hardness = item.baked() ? item.hardness() : 0;
            mineable[id] = tier >= hardness;
            time[id] = 50f + (scaling ? hardness * 15f : 15f);
        }
    }

    /** {@code canMine(item)}: the type's tier reaches the item's hardness; false for no item (-1). */
    public boolean canMine(int item){
        return item >= 0 && item < mineable.length && mineable[item];
    }

    /** Frames of {@code mineTimer} it takes to mine one {@code item}. */
    public float time(int item){
        return time[item];
    }
}
