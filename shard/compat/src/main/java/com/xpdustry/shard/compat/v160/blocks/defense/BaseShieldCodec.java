package com.xpdustry.shard.compat.v160.blocks.defense;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.defense.*;
import com.xpdustry.shard.sim.building.*;

/** {@code BaseShieldBuild} (version 1): {@code smoothRadius}, then {@code broken}; revision 0 has neither. */
@Register
public final class BaseShieldCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("base-shield");

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
        return revision >= 0 && revision <= 1;
    }

    @Override
    public void write(Building building, Writes out, ContentHeader.IdMapping ids){
        var baseShield = (BaseShield)building;
        out.f(baseShield.smoothRadius);
        out.bool(baseShield.broken);
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        var baseShield = (BaseShield)building;
        if(revision >= 1){
            baseShield.smoothRadius = in.f();
            baseShield.broken = in.bool();
        }
    }

    @Override
    public void skip(Tape in, int revision){
        if(revision >= 1){
            in.f();
            in.bool();
        }
    }
}
