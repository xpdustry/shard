package com.xpdustry.shard.sim.blocks.liquid;

import com.xpdustry.shard.sim.building.*;

/**
 * Vanilla's {@code LiquidBlock.LiquidBuild} (LiquidBlock.java:76), which only draws: a building of a plain
 * {@code LiquidBlock} holds liquid and does nothing with it. No v160.7 block uses it directly (tanks are liquid
 * routers); it is here for content that does.
 */
@Kind("liquid-block")
public final class LiquidBlock extends Building{
}
