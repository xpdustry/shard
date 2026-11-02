package com.xpdustry.shard.compat.v160.blocks.distribution;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.ContentType;
import com.xpdustry.shard.sim.blocks.distribution.*;
import com.xpdustry.shard.sim.building.*;

/**
 * {@code DirectionalUnloaderBuild.write}/{@code read} (DirectionalUnloader.java:153): the unload item's id as a short
 * (-1 for none), then {@code offset} as a short. The timer isn't saved.
 */
@Register
public final class DirectionalUnloaderCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("directional-unloader");

    @Override
    public KindKey kind(){
        return KIND;
    }

    @Override
    public int revision(){
        return 0;
    }

    @Override
    public boolean reads(int revision){
        return revision == 0;
    }

    @Override
    public void write(Building building, Writes out, ContentHeader.IdMapping ids){
        var unloader = (DirectionalUnloader)building;
        out.s(unloader.unloadItem == -1 ? -1 : ids.requireStream(ContentType.ITEM, unloader.unloadItem));
        out.s(unloader.offset);
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        var unloader = (DirectionalUnloader)building;
        int item = in.s();
        unloader.unloadItem = item == -1 ? -1 : ids.toCurrent(ContentType.ITEM, item);
        unloader.offset = in.s();
    }

    @Override
    public void skip(Tape in, int revision){
        in.s();
        in.s();
    }
}
