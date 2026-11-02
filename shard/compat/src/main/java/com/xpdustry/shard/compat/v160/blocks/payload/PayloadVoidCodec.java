package com.xpdustry.shard.compat.v160.blocks.payload;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.payload.*;
import com.xpdustry.shard.sim.building.*;

/** The payload void's save bytes (PayloadVoid.java), revision 0: the payload block's body only. */
@Register
public final class PayloadVoidCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("payload-void");

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
        PayloadBodies.write((PayloadVoid)building, out, ids);
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        PayloadBodies.read((PayloadVoid)building, in, ids);
    }

    @Override
    public void skip(Tape in, int revision){
        PayloadBodies.skipBlockBody(in);
    }
}
