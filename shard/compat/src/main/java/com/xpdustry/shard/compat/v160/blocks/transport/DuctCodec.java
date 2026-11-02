package com.xpdustry.shard.compat.v160.blocks.transport;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.transport.*;
import com.xpdustry.shard.sim.building.*;

/**
 * {@code DuctBuild.write}/{@code read} (Duct.java:271): the direction the item came from as a byte, from revision 1.
 * The item held is the first one in the base's items.
 */
@Register
public final class DuctCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("duct");

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
        out.b(((Duct)building).recDir);
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        var duct = (Duct)building;
        if(revision >= 1) duct.recDir = in.b();
        duct.loaded();
    }

    @Override
    public void skip(Tape in, int revision){
        if(revision >= 1) in.b();
    }
}
