package com.xpdustry.shard.compat.v160.filters;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;

import java.util.*;

/**
 * Vanilla's {@code RandomItemFilter}: storage blocks (not cores) get random amounts of the listed items. It has no class
 * tag, so only a map naming its class reaches it.
 */
public record RandomItemFilter(List<Drop> drops, float chance) implements GenerateFilter{
    /** An {@code ItemStack}: up to {@code amount} of {@code item}. */
    public record Drop(ItemDef item, int amount){
    }

    public RandomItemFilter{
        drops = List.copyOf(drops);
    }

    @Override
    public boolean post(){
        return true;
    }

    @Override
    public void apply(FilterGrid grid, GenerateInput in){
        // Vanilla quirk: every tile of a storage block counts, so a vault fills nine times.
        for(int pos = 0; pos < grid.size(); pos++){
            int block = grid.block(pos);
            if(grid.isStorage(block) && !grid.isCore(block)){
                Building building = grid.building(pos);
                for(Drop stack : drops){
                    if(Mathf.chance(grid.rand, chance)){
                        int amount = Math.min(Mathf.random(grid.rand, stack.amount()), grid.def(block).itemCapacity());
                        building.items.items[stack.item().id()] += amount;
                        building.items.total += amount;
                    }
                }
            }
        }
    }
}
