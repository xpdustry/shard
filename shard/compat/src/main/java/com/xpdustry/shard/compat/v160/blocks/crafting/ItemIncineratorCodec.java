package com.xpdustry.shard.compat.v160.blocks.crafting;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;

/** {@code ItemIncineratorBuild} has no {@code write}: the base is all a save keeps, and a loaded one starts cold. */
@Register
public final class ItemIncineratorCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("item-incinerator");

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
