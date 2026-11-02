package com.xpdustry.shard.compat.v160.blocks.sandbox;

import com.xpdustry.shard.common.registry.*;

/** Vanilla's plain {@code Building} (the power void): no body. */
@Register
public final class PlainBuildingCodec extends NoBodyCodec{
    public PlainBuildingCodec(){
        super("block");
    }
}
