package com.xpdustry.shard.sim.building.consume;

import com.xpdustry.shard.sim.building.*;

/**
 * Vanilla {@code ConsumeItems} and {@code ConsumeItemDynamic}: item stacks taken on {@link #trigger}, either from the
 * definition or, when {@link #hook} is set, from {@link Building#consumedItems()} (a factory's current plan).
 */
public final class ConsumeItems extends Consume{
    /** The fixed stacks, or null for a dynamic consumer. */
    public final Stacks items;
    /** The dynamic consumer's hook name ({@code hook:unit-factory.items}), or null. */
    public final String hook;

    private ConsumeItems(Flags flags, Stacks items, String hook){
        super(flags);
        this.items = items;
        this.hook = hook;
    }

    /** {@code ConsumeItems}. */
    public static ConsumeItems fixed(Flags flags, Stacks items){
        return new ConsumeItems(flags, items, null);
    }

    /** {@code ConsumeItemDynamic}. */
    public static ConsumeItems dynamic(Flags flags, String hook){
        return new ConsumeItems(flags, null, hook);
    }

    public boolean dynamic(){
        return items == null;
    }

    /** The stacks {@code building} needs now. */
    public Stacks stacks(Building building){
        return items != null ? items : building.consumedItems();
    }

    @Override
    public void trigger(Building building){
        Stacks stacks = stacks(building);
        float multiplier = multiplier(building);
        for(int i = 0; i < stacks.size(); i++){
            building.items.remove(stacks.id(i), Math.round(stacks.amount(i) * multiplier));
        }
    }

    @Override
    public float efficiency(Building building){
        return building.consumeTriggerValid() || building.items.has(stacks(building), multiplier(building)) ? 1f : 0f;
    }
}
