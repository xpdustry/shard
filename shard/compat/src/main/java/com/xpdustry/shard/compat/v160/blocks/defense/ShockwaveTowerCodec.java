package com.xpdustry.shard.compat.v160.blocks.defense;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;

/** {@code ShockwaveTowerBuild} has no {@code write}: a save keeps only the base, and the reload restarts at its random draw. */
@Register
public final class ShockwaveTowerCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("shockwave-tower");

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
