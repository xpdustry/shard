package com.xpdustry.shard.compat.v160.blocks.power;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.power.*;
import com.xpdustry.shard.sim.building.*;

/** {@code LightBuild.write}/{@code read}: the colour, RGBA8888. {@code smoothTime} isn't saved. */
@Register
public final class LightBlockCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("light-block");

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
        out.i(((LightBlock)building).color);
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        ((LightBlock)building).color = in.i();
    }

    @Override
    public void skip(Tape in, int revision){
        in.i();
    }
}
