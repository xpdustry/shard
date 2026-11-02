package com.xpdustry.shard.compat.v160.blocks.power;

import com.xpdustry.shard.common.registry.*;

/** {@code BatteryBuild}: the batteries: no body. */
@Register
public final class BatteryCodec extends NoBodyCodec{
    public BatteryCodec(){
        super("battery");
    }
}
