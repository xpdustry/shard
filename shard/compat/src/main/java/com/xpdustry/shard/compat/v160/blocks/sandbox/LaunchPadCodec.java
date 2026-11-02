package com.xpdustry.shard.compat.v160.blocks.sandbox;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.sandbox.*;
import com.xpdustry.shard.sim.building.*;

/** {@code LaunchPadBuild.write}/{@code read}, revision 1: the launch counter, which revision 0 lacks. */
@Register
public final class LaunchPadCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("launch-pad");

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
        out.f(((LaunchPad)building).launchCounter);
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        if(revision >= 1) ((LaunchPad)building).launchCounter = in.f();
    }

    @Override
    public void skip(Tape in, int revision){
        if(revision >= 1) in.f();
    }
}
