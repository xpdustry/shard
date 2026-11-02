package com.xpdustry.shard.compat.v160.blocks.sandbox;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.ContentType;
import com.xpdustry.shard.sim.blocks.sandbox.*;
import com.xpdustry.shard.sim.building.*;

/**
 * {@code LiquidSourceBuild.write}/{@code read} (LiquidSource.java:104): the liquid's id, -1 for none; a short since
 * revision 1, a byte before.
 */
@Register
public final class LiquidSourceCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("liquid-source");

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
        int liquid = ((LiquidSource)building).source;
        out.s(liquid == -1 ? -1 : ids.requireStream(ContentType.LIQUID, liquid));
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        // content.liquid(id) under the file's mapper: -1 is none
        int id = revision == 1 ? in.s() : in.b();
        ((LiquidSource)building).source = ids.toCurrent(ContentType.LIQUID, id);
    }

    @Override
    public void skip(Tape in, int revision){
        if(revision == 1){
            in.s();
        }else{
            in.b();
        }
    }
}
