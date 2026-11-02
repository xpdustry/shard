package com.xpdustry.shard.compat.v160.blocks.transport;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;

/** The surge router's save bytes: its {@code DuctRouterBuild}'s ({@link DuctRouterCodec}); {@code unloading} isn't saved. */
@Register
public final class StackRouterCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("stack-router");

    @Override
    public KindKey kind(){
        return KIND;
    }

    @Override
    public int revision(){
        return 1;
    }

    @Override
    public boolean reads(int revision){
        return revision == 0 || revision == 1;
    }

    @Override
    public void write(Building building, Writes out, ContentHeader.IdMapping ids){
        DuctRouterCodec.writeBody(building, out, ids);
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        DuctRouterCodec.readBody(building, in, revision, ids);
    }

    @Override
    public void skip(Tape in, int revision){
        if(revision >= 1) in.s();
    }
}
