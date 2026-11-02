package com.xpdustry.shard.compat.v160.blocks.transport;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.transport.*;
import com.xpdustry.shard.sim.building.*;

/** The armored conveyor's save bytes: {@link ConveyorBodies}, revisions 0 and 1. */
@Register
public final class ArmoredConveyorCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("armored-conveyor");

    @Override
    public KindKey kind(){
        return KIND;
    }

    @Override
    public int revision(){
        return ConveyorBodies.REVISION;
    }

    @Override
    public boolean reads(int revision){
        return revision == 0 || revision == 1;
    }

    @Override
    public void write(Building building, Writes out, ContentHeader.IdMapping ids){
        ConveyorBodies.write((ConveyorBuild)building, out, ids);
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        ConveyorBodies.read((ConveyorBuild)building, in, revision, ids);
    }

    @Override
    public void skip(Tape in, int revision){
        ConveyorBodies.skip(in, revision);
    }
}
