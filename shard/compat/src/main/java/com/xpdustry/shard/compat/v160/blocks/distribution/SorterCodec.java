package com.xpdustry.shard.compat.v160.blocks.distribution;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.ContentType;
import com.xpdustry.shard.sim.blocks.distribution.*;
import com.xpdustry.shard.sim.building.*;

/**
 * {@code SorterBuild.write}/{@code read} (Sorter.java:157): the sort item's id as a short, -1 for none. Revision 1
 * also held an item buffer, which is read and dropped.
 */
@Register
public final class SorterCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("sorter");

    @Override
    public KindKey kind(){
        return KIND;
    }

    @Override
    public int revision(){
        return 2;
    }

    @Override
    public boolean reads(int revision){
        return revision >= 0 && revision <= 2;
    }

    @Override
    public void write(Building building, Writes out, ContentHeader.IdMapping ids){
        int item = ((Sorter)building).sortItem;
        out.s(item == -1 ? -1 : ids.requireStream(ContentType.ITEM, item));
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        ((Sorter)building).sortItem = ids.toCurrent(ContentType.ITEM, in.s());
        if(revision == 1) ItemBuffers.skip(in);
    }

    @Override
    public void skip(Tape in, int revision){
        in.s();
        if(revision == 1) ItemBuffers.skip(in);
    }
}
