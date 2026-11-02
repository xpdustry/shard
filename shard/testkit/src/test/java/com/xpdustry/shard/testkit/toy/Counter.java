package com.xpdustry.shard.testkit.toy;

import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Adds {@code step} to {@code total} every tick and, when configured with an item, one of it to its items every 10
 * ticks. Its Java fields don't carry the names the projection wants, so {@link CounterAdapter} renames them, which is
 * what a family owner's adapter does for a real kind.
 */
@Kind("toy-counter")
public final class Counter extends Building{
    /** The configured item id, or -1. */
    public int itemId = -1;
    public int step = 1;
    public int sum;
    public Building target;

    @Override
    public void updateTile(){
        sum += step;
        if(itemId >= 0 && sum % 10 == 0){
            items.items[itemId]++;
            items.total++;
        }
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (Counter)into;
        out.itemId = itemId;
        out.step = step;
        out.sum = sum;
        out.target = context.map(target);
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.i(itemId).i(step).i(sum).i(target == null ? -1 : target.id());
    }
}
