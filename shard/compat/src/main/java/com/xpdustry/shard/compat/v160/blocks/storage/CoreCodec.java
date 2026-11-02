package com.xpdustry.shard.compat.v160.blocks.storage;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.storage.*;
import com.xpdustry.shard.sim.building.*;

/**
 * {@code CoreBuild.write}/{@code read} (CoreBlock.java:917), revision 1: the RTS command position as
 * {@code TypeIO.writeVecNullable}, two floats that are both NaN for none. Revision 0 has nothing after the base.
 */
@Register
public final class CoreCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("core-block");

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
        Vec2 pos = ((Core)building).commandPos;
        out.f(pos == null ? Float.NaN : pos.x);
        out.f(pos == null ? Float.NaN : pos.y);
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        if(revision < 1) return;
        float x = in.f(), y = in.f();
        ((Core)building).commandPos = Float.isNaN(x) || Float.isNaN(y) ? null : new Vec2(x, y);
    }

    @Override
    public void skip(Tape in, int revision){
        if(revision < 1) return;
        in.f();
        in.f();
    }
}
