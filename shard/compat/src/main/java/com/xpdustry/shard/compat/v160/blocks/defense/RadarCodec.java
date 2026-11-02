package com.xpdustry.shard.compat.v160.blocks.defense;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.defense.*;
import com.xpdustry.shard.sim.building.*;

/** {@code RadarBuild.write}/{@code read}: {@code progress}. */
@Register
public final class RadarCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("radar");

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
        var radar = (Radar)building;
        out.f(radar.progress);
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        var radar = (Radar)building;
        radar.progress = in.f();
    }

    @Override
    public void skip(Tape in, int revision){
        in.f();
    }
}
