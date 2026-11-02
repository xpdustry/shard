package com.xpdustry.shard.sim.building.consume;

import com.xpdustry.shard.sim.building.*;

/**
 * Vanilla {@code ConsumeLiquids} and {@code ConsumeLiquidsDynamic}: several liquids at fixed rates, either from the
 * definition or, when {@link #hook} is set, from {@link Building#consumedLiquids()}.
 *
 * <p>Vanilla quirk kept: the fixed form scales {@code edelta} by {@code efficiencyScale()} for its efficiency, the
 * dynamic form doesn't.
 */
public final class ConsumeLiquids extends Consume{
    /** The fixed stacks, or null for a dynamic consumer. */
    public final Stacks liquids;
    /** The dynamic consumer's hook name ({@code hook:unit-assembler.liquids}), or null. */
    public final String hook;

    private ConsumeLiquids(Flags flags, Stacks liquids, String hook){
        super(flags);
        this.liquids = liquids;
        this.hook = hook;
    }

    /** {@code ConsumeLiquids}. */
    public static ConsumeLiquids fixed(Flags flags, Stacks liquids){
        return new ConsumeLiquids(flags, liquids, null);
    }

    /** {@code ConsumeLiquidsDynamic}. */
    public static ConsumeLiquids dynamic(Flags flags, String hook){
        return new ConsumeLiquids(flags, null, hook);
    }

    public boolean dynamic(){
        return liquids == null;
    }

    private Stacks stacks(Building building){
        return liquids != null ? liquids : building.consumedLiquids();
    }

    @Override
    public void update(Building building){
        Stacks stacks = stacks(building);
        float multiplier = multiplier(building);
        for(int i = 0; i < stacks.size(); i++){
            building.liquids.remove(stacks.id(i), stacks.amountF(i) * building.edelta() * multiplier);
        }
    }

    @Override
    public float efficiency(Building building){
        Stacks stacks = stacks(building);
        float multiplier = multiplier(building);
        float ed = dynamic() ? building.edelta() : building.edelta() * building.efficiencyScale();
        if(ed <= 0.00000001f) return 0f;
        float min = 1f;
        for(int i = 0; i < stacks.size(); i++){
            min = Math.min(building.liquids.get(stacks.id(i)) / (stacks.amountF(i) * ed * multiplier), min);
        }
        return min;
    }
}
