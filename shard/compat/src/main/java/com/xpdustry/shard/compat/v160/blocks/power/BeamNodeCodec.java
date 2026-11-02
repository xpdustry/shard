package com.xpdustry.shard.compat.v160.blocks.power;

import com.xpdustry.shard.common.registry.*;

/** {@code BeamNodeBuild}: the beam node and the beam tower: no body; a loaded node recasts its beams. */
@Register
public final class BeamNodeCodec extends NoBodyCodec{
    public BeamNodeCodec(){
        super("beam-node");
    }
}
