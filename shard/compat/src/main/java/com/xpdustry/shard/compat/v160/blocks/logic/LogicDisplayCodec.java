package com.xpdustry.shard.compat.v160.blocks.logic;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.logic.*;
import com.xpdustry.shard.sim.building.*;

/**
 * {@code LogicDisplayBuild.write}/{@code read}, version 1: whether it has a transform, then its 9 floats. Revision 0
 * had nothing. Tile displays share it ({@link TileableLogicDisplayCodec}).
 */
@Register
public final class LogicDisplayCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("logic-display");
    static final int MAT = 9;

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
        writeTransform(((LogicDisplay)building).transform, out);
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        if(revision >= 1) ((LogicDisplay)building).transform = readTransform(in);
    }

    @Override
    public void skip(Tape in, int revision){
        if(revision >= 1 && in.bool()) in.b(MAT * 4);
    }

    static void writeTransform(float[] transform, Writes out){
        out.bool(transform != null);
        if(transform != null) for(float value : transform) out.f(value);
    }

    static float[] readTransform(Reads in){
        if(!in.bool()) return null;
        var transform = new float[MAT];
        for(int i = 0; i < MAT; i++) transform[i] = in.f();
        return transform;
    }
}
