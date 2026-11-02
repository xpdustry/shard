package com.xpdustry.shard.sim.building.consume;

import com.xpdustry.shard.sim.building.*;

/** Vanilla {@code ConsumeLiquid}: one liquid at a fixed rate. */
public final class ConsumeLiquid extends ConsumeLiquidBase{
    public final int liquid;

    public ConsumeLiquid(Flags flags, int liquid, float amount){
        super(flags, amount);
        this.liquid = liquid;
    }

    @Override
    public void update(Building building){
        building.liquids.remove(liquid, amount * building.edelta() * multiplier(building));
    }

    @Override
    public float efficiency(Building building){
        float ed = building.edelta() * building.efficiencyScale();
        if(ed <= 0.00000001f) return 0f;
        // there can be more liquid than necessary, so cap at 1
        return Math.min(building.liquids.get(liquid) / (amount * ed * multiplier(building)), 1f);
    }

    @Override
    public boolean consumes(int liquid){
        return liquid == this.liquid;
    }
}
