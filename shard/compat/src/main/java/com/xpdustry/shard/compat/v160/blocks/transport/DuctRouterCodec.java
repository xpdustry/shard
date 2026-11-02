package com.xpdustry.shard.compat.v160.blocks.transport;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.ContentType;
import com.xpdustry.shard.sim.blocks.transport.*;
import com.xpdustry.shard.sim.building.*;

/**
 * {@code DuctRouterBuild.write}/{@code read} (DuctRouter.java:192): the sort item's id as a short, from revision 1. The
 * surge router's ({@link StackRouterCodec}) are the same.
 */
@Register
public final class DuctRouterCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("duct-router");

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
        writeBody(building, out, ids);
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        readBody(building, in, revision, ids);
    }

    @Override
    public void skip(Tape in, int revision){
        if(revision >= 1) in.s();
    }

    static void writeBody(Building building, Writes out, ContentHeader.IdMapping ids){
        int item = ((DuctRouterBuild)building).sortItem;
        out.s(item == -1 ? -1 : ids.requireStream(ContentType.ITEM, item));
    }

    static void readBody(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        if(revision >= 1) ((DuctRouterBuild)building).sortItem = ids.toCurrent(ContentType.ITEM, in.s());
    }
}
