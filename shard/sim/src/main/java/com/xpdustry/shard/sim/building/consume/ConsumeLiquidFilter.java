package com.xpdustry.shard.sim.building.consume;

import com.xpdustry.shard.sim.building.*;

/**
 * Vanilla {@code ConsumeLiquidFilter} and the subclasses that only change its data: any accepted liquid at a fixed
 * rate. {@code ConsumeLiquidFlammable} accepts liquids at or above a flammability, which is their multiplier;
 * {@code ConsumeCoolant} accepts coolants by temperature, flammability and phase. Evaluated once per epoch by
 * {@link Consumers}.
 *
 * <p>Vanilla quirk kept: unlike {@link ConsumeLiquid}, {@link #efficiency} doesn't scale by {@code efficiencyScale()}.
 */
public final class ConsumeLiquidFilter extends ConsumeLiquidBase{
    private final boolean[] filter;
    private final float[] multipliers;

    /** {@code filter[liquid]} accepts it; {@code multipliers[liquid]} is its {@code liquidEfficiencyMultiplier}. */
    public ConsumeLiquidFilter(Flags flags, float amount, boolean[] filter, float[] multipliers){
        super(flags, amount);
        if(filter.length != multipliers.length) throw new IllegalArgumentException("one multiplier per liquid");
        this.filter = filter.clone();
        this.multipliers = multipliers.clone();
    }

    /** {@code getConsumed}: the current liquid if accepted and present, else the first accepted one present, or -1. */
    public int consumed(Building building){
        LiquidModule liquids = building.liquids;
        if(consumes(liquids.current()) && liquids.currentAmount() > 0) return liquids.current();
        for(int liquid = 0; liquid < filter.length; liquid++){
            if(filter[liquid] && liquids.get(liquid) > 0) return liquid;
        }
        return -1;
    }

    @Override
    public void update(Building building){
        int liquid = consumed(building);
        if(liquid != -1) building.liquids.remove(liquid, amount * building.edelta() * multiplier(building));
    }

    @Override
    public float efficiency(Building building){
        int liquid = consumed(building);
        float ed = building.edelta();
        if(ed <= 0.00000001f) return 0f;
        return liquid != -1 ? Math.min(building.liquids.get(liquid) / (amount * ed * multiplier(building)), 1f) : 0f;
    }

    @Override
    public float efficiencyMultiplier(Building building){
        int liquid = consumed(building);
        return liquid == -1 ? 0f : multipliers[liquid];
    }

    @Override
    public boolean consumes(int liquid){
        return liquid >= 0 && liquid < filter.length && filter[liquid];
    }
}
