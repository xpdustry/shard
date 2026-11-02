package com.xpdustry.shard.compat.v160.blocks.liquid;

import com.xpdustry.shard.common.registry.*;

/** The armored conduit's save bytes: none ({@code ArmoredConduitBuild} has no {@code write}). */
@Register
public final class ArmoredConduitCodec extends NoBodyCodec{
    public ArmoredConduitCodec(){
        super("armored-conduit");
    }
}
