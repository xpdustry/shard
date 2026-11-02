package com.xpdustry.shard.sim.blocks.liquid;

import com.xpdustry.shard.sim.blocks.distribution.*;
import com.xpdustry.shard.sim.building.*;

/**
 * Vanilla's {@code LiquidBridge.LiquidBridgeBuild} (LiquidBridge.java:22): the bridge conduit and the phase conduit,
 * linked as the item bridges are ({@link ItemBridgeBuild}). Once warmed up to a quarter, a linked bridge moves its
 * current liquid into its link every tick ({@code moveLiquid}), counting as moved when more than 0.05 goes; an unlinked
 * one dumps it to its neighbours (scaling 1), never toward a bridge feeding it. It takes a liquid while it holds that one
 * or less than 0.2 of its current one, from its team, from any side but its link's.
 */
@Kind("liquid-bridge")
public final class LiquidBridge extends ItemBridgeBuild{
    @Override
    protected void updateTransport(Building other){
        if(warmup >= 0.25f){
            moved |= moveLiquid(other, liquids.current()) > 0.05f;
        }
    }

    @Override
    protected void doDump(){
        dumpLiquid(liquids.current(), 1f);
    }
}
