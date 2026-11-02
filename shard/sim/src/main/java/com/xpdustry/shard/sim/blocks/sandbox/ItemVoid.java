package com.xpdustry.shard.sim.blocks.sandbox;

import com.xpdustry.shard.sim.building.*;

/**
 * Vanilla's {@code ItemVoid.ItemVoidBuild}: takes every item while enabled, from any team, and keeps none. Vanilla
 * counts them in a display-only flow module, which a server never reads.
 */
@Kind("item-void")
public final class ItemVoid extends Building{
    @Override
    public boolean acceptItem(Building source, int item){
        return enabled;
    }

    @Override
    public void handleItem(Building source, int item){
    }
}
