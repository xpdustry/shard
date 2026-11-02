package com.xpdustry.shard.common.struct;

import com.xpdustry.shard.common.math.*;

/** The sizing rule shared by Arc's open-addressing tables ({@code ObjectSet.tableSize}). */
final class Tables{
    static int tableSize(int capacity, float loadFactor){
        if(capacity < 0) throw new IllegalArgumentException("capacity must be >= 0: " + capacity);
        int tableSize = Mathf.nextPowerOfTwo(Math.max(2, (int)Math.ceil(capacity / loadFactor)));
        if(tableSize > 1 << 30) throw new IllegalArgumentException("The required capacity is too large: " + capacity);
        return tableSize;
    }

    static void checkLoadFactor(float loadFactor){
        if(loadFactor <= 0f || loadFactor >= 1f)
            throw new IllegalArgumentException("loadFactor must be > 0 and < 1: " + loadFactor);
    }

    private Tables(){}
}
