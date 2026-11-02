package com.xpdustry.shard.compat.v160.blocks.defense;

import com.xpdustry.shard.common.registry.*;

/** {@code ShockMineBuild}: no body; its damage timer isn't saved. */
@Register
public final class ShockMineCodec extends NoBodyCodec{
    public ShockMineCodec(){
        super("shock-mine");
    }
}
