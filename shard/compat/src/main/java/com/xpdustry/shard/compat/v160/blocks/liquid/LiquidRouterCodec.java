package com.xpdustry.shard.compat.v160.blocks.liquid;

import com.xpdustry.shard.common.registry.*;

/** The liquid router's, container's and tank's save bytes: none ({@code LiquidRouterBuild} has no {@code write}). */
@Register
public final class LiquidRouterCodec extends NoBodyCodec{
    public LiquidRouterCodec(){
        super("liquid-router");
    }
}
