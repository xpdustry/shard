package com.xpdustry.shard.compat.v160.blocks.units;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.blocks.payload.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.units.*;
import com.xpdustry.shard.sim.building.*;

/**
 * {@code UnitFactoryBuild} (UnitFactory.java:480-506), version 3: the payload block's body ({@code payVector},
 * {@code payRotation}, the payload), then {@code progress}, {@code currentPlan} as a short, the rally point (from
 * revision 2) and the command (from revision 3).
 */
@Register
public final class UnitFactoryCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("unit-factory");

    @Override
    public KindKey kind(){
        return KIND;
    }

    @Override
    public int revision(){
        return 3;
    }

    @Override
    public boolean reads(int revision){
        return revision >= 0 && revision <= 3;
    }

    @Override
    public void write(Building building, Writes out, ContentHeader.IdMapping ids){
        var factory = (UnitFactory)building;
        PayloadBodies.write(factory, out, ids);
        out.f(factory.progress);
        out.s(factory.currentPlan);
        UnitBlockBodies.writeVec(out, factory.commandPos);
        UnitBlockBodies.writeCommand(out, factory.command);
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        var factory = (UnitFactory)building;
        PayloadBodies.read(factory, in, ids);
        factory.progress = in.f();
        factory.currentPlan = in.s();
        if(revision >= 2) factory.commandPos = UnitBlockBodies.readVec(in);
        if(revision >= 3) factory.command = UnitBlockBodies.readCommand(in, factory, ids);
    }

    @Override
    public void skip(Tape in, int revision){
        PayloadBodies.skipBlockBody(in);
        in.f();
        in.s();
        if(revision >= 2){
            in.f();
            in.f();
        }
        if(revision >= 3) in.b();
    }
}
