package com.xpdustry.shard.compat.v160.blocks.logic;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.logic.*;
import com.xpdustry.shard.sim.building.*;

/** {@code CanvasBuild.write}/{@code read}: the image's length and bytes; an image of another size is skipped. */
@Register
public final class CanvasBlockCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("canvas-block");

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
        byte[] data = ((CanvasBlock)building).data;
        out.i(data.length);
        out.b(data);
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        byte[] data = ((CanvasBlock)building).data;
        int length = in.i();
        if(data.length == length) in.b(data);
        else in.skip(length);
    }

    @Override
    public void skip(Tape in, int revision){
        in.b(in.i());
    }
}
