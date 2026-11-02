package com.xpdustry.shard.compat.v160.blocks.liquid;

import com.xpdustry.shard.common.registry.*;

/** The conduit's save bytes: none ({@code ConduitBuild} has no {@code write}). */
@Register
public final class ConduitCodec extends NoBodyCodec{
    public ConduitCodec(){
        super("conduit");
    }
}
