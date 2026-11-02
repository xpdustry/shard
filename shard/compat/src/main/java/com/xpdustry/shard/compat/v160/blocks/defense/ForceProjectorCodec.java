package com.xpdustry.shard.compat.v160.blocks.defense;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.defense.*;
import com.xpdustry.shard.sim.building.*;

/** {@code ForceBuild.write}/{@code read}: {@code broken}, {@code buildup}, {@code radscl}, {@code warmup}, {@code phaseHeat}. */
@Register
public final class ForceProjectorCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("force-projector");

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
        var forceProjector = (ForceProjector)building;
        out.bool(forceProjector.broken);
        out.f(forceProjector.buildup);
        out.f(forceProjector.radscl);
        out.f(forceProjector.warmup);
        out.f(forceProjector.phaseHeat);
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        var forceProjector = (ForceProjector)building;
        forceProjector.broken = in.bool();
        forceProjector.buildup = in.f();
        forceProjector.radscl = in.f();
        forceProjector.warmup = in.f();
        forceProjector.phaseHeat = in.f();
    }

    @Override
    public void skip(Tape in, int revision){
        in.bool();
        in.f();
        in.f();
        in.f();
        in.f();
    }
}
