package com.xpdustry.shard.compat.v160.blocks.logic;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;

/** {@code SwitchBuild.write}/{@code read}, version 1: {@code enabled} again, after the base. Revision 0 had nothing. */
@Register
public final class SwitchBlockCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("switch-block");

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
        out.bool(building.enabled);
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        if(revision == 1) building.enabled = in.bool();
    }

    @Override
    public void skip(Tape in, int revision){
        if(revision == 1) in.bool();
    }
}
