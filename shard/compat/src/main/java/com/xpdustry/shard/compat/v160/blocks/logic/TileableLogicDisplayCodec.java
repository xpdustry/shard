package com.xpdustry.shard.compat.v160.blocks.logic;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.logic.*;
import com.xpdustry.shard.sim.building.*;

/** {@code TileableLogicDisplayBuild}'s save is {@code LogicDisplayBuild}'s, version 1 ({@link LogicDisplayCodec}). */
@Register
public final class TileableLogicDisplayCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("tileable-logic-display");

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
        LogicDisplayCodec.writeTransform(((TileableLogicDisplay)building).transform, out);
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        if(revision >= 1) ((TileableLogicDisplay)building).transform = LogicDisplayCodec.readTransform(in);
    }

    @Override
    public void skip(Tape in, int revision){
        if(revision >= 1 && in.bool()) in.b(LogicDisplayCodec.MAT * 4);
    }
}
