package com.xpdustry.shard.compat.v160.blocks.production;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.production.*;
import com.xpdustry.shard.sim.building.*;

/**
 * {@code BeamDrillBuild.write}/{@code read} (BeamDrill.java:419), revision 1: {@code time}, then {@code warmup};
 * revision 0 has neither. The beams and what they face are found again on the first update.
 */
@Register
public final class BeamDrillCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("beam-drill");

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
        var drill = (BeamDrill)building;
        out.f(drill.time);
        out.f(drill.warmup);
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        var drill = (BeamDrill)building;
        if(revision >= 1){
            drill.time = in.f();
            drill.warmup = in.f();
        }
    }

    @Override
    public void skip(Tape in, int revision){
        if(revision >= 1){
            in.f();
            in.f();
        }
    }
}
