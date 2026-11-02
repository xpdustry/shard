package com.xpdustry.shard.sim.blocks.sandbox;

import com.xpdustry.shard.sim.building.*;

/**
 * Vanilla's {@code LiquidVoid.LiquidVoidBuild} (LiquidVoid.java:28): takes every liquid while enabled, from any team,
 * and keeps none. Vanilla counts it in the module's display-only flow statistics, which a server never reads. When
 * construction finishes it ({@code placed()}), it empties its module.
 */
@Kind("liquid-void")
public final class LiquidVoid extends Building{
    @Override
    public void placed(){
        super.placed();
        liquids.clear();
    }

    @Override
    public boolean acceptLiquid(Building source, int liquid){
        return enabled;
    }

    @Override
    public void handleLiquid(Building source, int liquid, float amount){
    }
}
