package com.xpdustry.shard.compat.v160.blocks.power;

import com.xpdustry.shard.common.registry.*;

/** {@code PowerSourceBuild}: the power source: no body. */
@Register
public final class PowerSourceCodec extends NoBodyCodec{
    public PowerSourceCodec(){
        super("power-source");
    }
}
