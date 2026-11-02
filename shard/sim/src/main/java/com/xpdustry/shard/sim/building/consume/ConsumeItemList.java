package com.xpdustry.shard.sim.building.consume;

import com.xpdustry.shard.sim.building.*;

/**
 * Vanilla {@code ConsumeItemList}: a filter over the items of a multiplier map. Unlike the plain filter, its
 * {@link #efficiencyMultiplier} is 1 when nothing is held ({@code itemMultipliers.get(null, 1f)}).
 */
public final class ConsumeItemList extends ConsumeItemFilter{
    public ConsumeItemList(Flags flags, boolean[] filter, float[] multipliers){
        super(flags, filter, multipliers);
    }

    @Override
    public float efficiencyMultiplier(Building building){
        int item = consumed(building);
        return item == -1 ? 1f : itemEfficiencyMultiplier(item);
    }
}
