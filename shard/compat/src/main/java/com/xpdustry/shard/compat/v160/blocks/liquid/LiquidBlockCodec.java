package com.xpdustry.shard.compat.v160.blocks.liquid;

import com.xpdustry.shard.common.registry.*;

/** A plain liquid block's save bytes: none ({@code LiquidBuild} has no {@code write}). */
@Register
public final class LiquidBlockCodec extends NoBodyCodec{
    public LiquidBlockCodec(){
        super("liquid-block");
    }
}
