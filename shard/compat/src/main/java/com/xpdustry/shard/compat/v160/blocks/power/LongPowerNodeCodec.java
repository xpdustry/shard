package com.xpdustry.shard.compat.v160.blocks.power;

import com.xpdustry.shard.common.registry.*;

/** {@code LongPowerNodeBuild}: the beam link; its {@code warmup} isn't saved: no body. */
@Register
public final class LongPowerNodeCodec extends NoBodyCodec{
    public LongPowerNodeCodec(){
        super("long-power-node");
    }
}
