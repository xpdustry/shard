package com.xpdustry.shard.compat.v160.blocks.sandbox;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;

/** The item void's save bytes: none ({@code ItemVoidBuild} has no {@code write}). */
@Register
public final class ItemVoidCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("item-void");

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
