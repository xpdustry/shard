package com.xpdustry.shard.compat.v160.blocks.defense;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.defense.*;
import com.xpdustry.shard.sim.building.*;

/** {@code MendBuild.write}/{@code read}: {@code heat}, then {@code phaseHeat}. */
@Register
public final class MendProjectorCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("mend-projector");

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
        var mendProjector = (MendProjector)building;
        out.f(mendProjector.heat);
        out.f(mendProjector.phaseHeat);
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        var mendProjector = (MendProjector)building;
        mendProjector.heat = in.f();
        mendProjector.phaseHeat = in.f();
    }

    @Override
    public void skip(Tape in, int revision){
        in.f();
        in.f();
    }
}
