package com.xpdustry.shard.compat.v160.blocks.sandbox;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.ContentType;
import com.xpdustry.shard.sim.blocks.sandbox.*;
import com.xpdustry.shard.sim.building.*;

/** {@code ItemSourceBuild.write}/{@code read}: the output item's id as a short, -1 for none. */
@Register
public final class ItemSourceCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("item-source");

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
        int item = ((ItemSource)building).outputItem;
        out.s(item == -1 ? -1 : ids.requireStream(ContentType.ITEM, item));
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        // content.item(id) under the file's mapper: -1 is none, an unknown id the first item
        ((ItemSource)building).outputItem = ids.toCurrent(ContentType.ITEM, in.s());
    }

    @Override
    public void skip(Tape in, int revision){
        in.s();
    }
}
