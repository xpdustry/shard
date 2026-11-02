package com.xpdustry.shard.compat.v160.blocks.transport;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;

/** The overflow duct's save bytes: none, as {@code OverflowDuctBuild} has no {@code write}. */
@Register
public final class OverflowDuctCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("overflow-duct");

    @Override
    public KindKey kind(){
        return KIND;
    }

    @Override
    public int revision(){
        return 0;
    }

    @Override
    public void write(Building building, Writes out, ContentHeader.IdMapping ids){
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
    }

    @Override
    public void skip(Tape in, int revision){
    }
}
