package com.xpdustry.shard.compat.v160.blocks.payload;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.payload.*;
import com.xpdustry.shard.sim.building.*;

/** The constructors' save bytes (BlockProducer.java:180, Constructor.java:88), revision 0: the payload block's body, {@code progress}, then the recipe as a short (-1 for none). */
@Register
public final class ConstructorCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("constructor");

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
        var constructor = (Constructor)building;
        PayloadBodies.write(constructor, out, ids);
        out.f(constructor.progress);
        out.s(constructor.recipe == -1 ? -1 : ids.requireStream(ContentType.BLOCK, constructor.recipe));
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        var constructor = (Constructor)building;
        PayloadBodies.read(constructor, in, ids);
        constructor.progress = in.f();
        constructor.recipe = PayloadBodies.content(ContentType.BLOCK, in.s(), ids);
    }

    @Override
    public void skip(Tape in, int revision){
        PayloadBodies.skipBlockBody(in);
        in.f();
        in.s();
    }
}
