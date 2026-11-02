package com.xpdustry.shard.compat.v160.blocks.defense;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;

/** The kinds whose building class has no {@code write}: the base is the whole chunk, and the body is empty at revision 0. */
abstract class NoBodyCodec implements BuildingCodec{
    private final KindKey kind;

    NoBodyCodec(String kind){
        this.kind = KindKey.of(kind);
    }

    @Override
    public final KindKey kind(){
        return kind;
    }

    @Override
    public final int revision(){
        return 0;
    }

    @Override
    public final void write(Building building, Writes out, ContentHeader.IdMapping ids){
    }

    @Override
    public final void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
    }

    @Override
    public final void skip(Tape in, int revision){
    }
}
