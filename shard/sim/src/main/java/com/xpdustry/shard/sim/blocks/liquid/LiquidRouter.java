package com.xpdustry.shard.sim.blocks.liquid;

import com.xpdustry.shard.sim.building.*;

/**
 * Vanilla's {@code LiquidRouter.LiquidRouterBuild} (LiquidRouter.java:25): the liquid router, and the liquid
 * containers and tanks, which are liquid routers with more room. Every tick it dumps its current liquid to its
 * neighbours ({@code dumpLiquid}, scaling 2), and it takes a liquid while it holds that one or less than 0.2 of another.
 * Logic can't disable it ({@code noUpdateDisabled}).
 */
@Kind("liquid-router")
public final class LiquidRouter extends Building{
    @Override
    public void updateTile(){
        dumpLiquid(liquids.current());
    }

    @Override
    public boolean acceptLiquid(Building source, int liquid){
        return liquids.current() == liquid || liquids.currentAmount() < 0.2f;
    }
}
