package com.xpdustry.shard.compat.v160.blocks.distribution;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.ContentType;
import com.xpdustry.shard.sim.blocks.distribution.*;
import com.xpdustry.shard.sim.building.*;

/**
 * {@code UnloaderBuild.write}/{@code read} (Unloader.java:296): the sort item's id, a short at revision 1 and a byte
 * at revision 0, -1 for none.
 */
@Register
public final class UnloaderCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("unloader");

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
        int item = ((Unloader)building).sortItem;
        out.s(item == -1 ? -1 : ids.requireStream(ContentType.ITEM, item));
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        int id = revision == 1 ? in.s() : in.b();
        ((Unloader)building).sortItem = id == -1 ? -1 : ids.toCurrent(ContentType.ITEM, id);
    }

    @Override
    public void skip(Tape in, int revision){
        if(revision == 1) in.s();
        else in.b();
    }
}
