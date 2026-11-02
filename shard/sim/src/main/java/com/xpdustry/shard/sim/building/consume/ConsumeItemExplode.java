package com.xpdustry.shard.sim.building.consume;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.sim.building.*;

/**
 * Vanilla {@code ConsumeItemExplode}: consumes nothing, but while an item at or above {@link #threshold}
 * explosiveness is held, damages the building now and then. Its efficiency is always 1.
 *
 * <p>Vanilla draws the chance from the global {@code Mathf.rand}; Shard draws it from the world's random.
 */
public final class ConsumeItemExplode extends ConsumeItemFilter{
    public final float damage, threshold, baseChance;
    private final float[] explosiveness;

    /** {@code explosiveness[item]} is each item's explosiveness; the filter is {@code explosiveness >= threshold}. */
    public ConsumeItemExplode(Flags flags, boolean[] filter, float[] explosiveness, float damage, float threshold, float baseChance){
        super(flags, filter, new float[filter.length]);
        this.explosiveness = explosiveness.clone();
        this.damage = damage;
        this.threshold = threshold;
        this.baseChance = baseChance;
    }

    @Override
    public void update(Building building){
        int item = consumed(building);
        if(item == -1) return;
        Rand rand = building.world().rand();
        if(building.rules().reactorExplosions && Mathf.chance(rand, building.delta() * baseChance * Mathf.clamp(explosiveness[item] - threshold))){
            building.damage(damage);
            // explodeEffect's position draws from the random even on a server
            float range = building.block().size() * 8 / 2f;
            Mathf.range(rand, range);
            Mathf.range(rand, range);
        }
    }

    @Override
    public void trigger(Building building){
    }

    @Override
    public float efficiency(Building building){
        return 1f;
    }

    @Override
    public float efficiencyMultiplier(Building building){
        return 1f;
    }
}
