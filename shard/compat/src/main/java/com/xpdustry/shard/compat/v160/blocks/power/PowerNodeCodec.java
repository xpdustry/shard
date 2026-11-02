package com.xpdustry.shard.compat.v160.blocks.power;

import com.xpdustry.shard.common.registry.*;

/** {@code PowerNodeBuild}: the power node, the large node and the surge tower: no body. */
@Register
public final class PowerNodeCodec extends NoBodyCodec{
    public PowerNodeCodec(){
        super("power-node");
    }
}
