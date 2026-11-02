package com.xpdustry.shard.sim.building.consume;

import com.xpdustry.shard.sim.building.*;

/**
 * Vanilla {@code ConsumeItemFilter} and the subclasses that only change its data: one item of any accepted kind,
 * taken on {@link #trigger}. The first accepted item held, in id order, is the one consumed.
 *
 * <ul>
 *   <li>{@code ConsumeItemFilter}: {@link #efficiencyMultiplier} is 1 per accepted item.</li>
 *   <li>{@code ConsumeItemEfficiency}: the same; its duration multipliers are stats only.</li>
 *   <li>{@code ConsumeItemFlammable}, {@code ConsumeItemRadioactive}, {@code ConsumeItemExplosive},
 *   {@code ConsumeItemCharged}: items at or above a threshold of one property, whose value is the multiplier.</li>
 * </ul>
 *
 * The filter and the multipliers are evaluated once per epoch, by {@link Consumers}.
 */
public class ConsumeItemFilter extends Consume{
    private final boolean[] filter;
    private final float[] multipliers;

    /** {@code filter[item]} accepts it; {@code multipliers[item]} is its {@code itemEfficiencyMultiplier}. */
    public ConsumeItemFilter(Flags flags, boolean[] filter, float[] multipliers){
        super(flags);
        if(filter.length != multipliers.length) throw new IllegalArgumentException("one multiplier per item");
        this.filter = filter.clone();
        this.multipliers = multipliers.clone();
    }

    /** Whether this consumer accepts {@code item} ({@code filter.get(item)}). */
    public final boolean accepts(int item){
        return item >= 0 && item < filter.length && filter[item];
    }

    /** {@code itemEfficiencyMultiplier(item)}. */
    public final float itemEfficiencyMultiplier(int item){
        return multipliers[item];
    }

    /** {@code getConsumed}: the first accepted item the building holds, in id order, or -1. */
    public final int consumed(Building building){
        ItemModule items = building.items;
        for(int item = 0; item < items.length(); item++){
            if(items.has(item) && accepts(item)) return item;
        }
        return -1;
    }

    @Override
    public void trigger(Building building){
        int item = consumed(building);
        if(item != -1) building.items.remove(item, 1);
    }

    @Override
    public float efficiency(Building building){
        return building.consumeTriggerValid() || consumed(building) != -1 ? 1f : 0f;
    }

    @Override
    public float efficiencyMultiplier(Building building){
        int item = consumed(building);
        return item == -1 ? 0f : itemEfficiencyMultiplier(item);
    }
}
