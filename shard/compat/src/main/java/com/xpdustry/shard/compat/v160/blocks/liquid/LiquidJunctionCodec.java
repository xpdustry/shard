package com.xpdustry.shard.compat.v160.blocks.liquid;

import com.xpdustry.shard.common.registry.*;

/** The liquid junction's save bytes: none ({@code LiquidJunctionBuild} has no {@code write}). */
@Register
public final class LiquidJunctionCodec extends NoBodyCodec{
    public LiquidJunctionCodec(){
        super("liquid-junction");
    }
}
