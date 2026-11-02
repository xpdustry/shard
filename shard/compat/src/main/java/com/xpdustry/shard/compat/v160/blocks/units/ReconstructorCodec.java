package com.xpdustry.shard.compat.v160.blocks.units;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.blocks.payload.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.units.*;
import com.xpdustry.shard.sim.building.*;

/**
 * {@code ReconstructorBuild} (Reconstructor.java:407-436), version 3: the payload block's body, then {@code progress}
 * (from revision 1), the rally point (from 2) and the command (from 3). {@code constructing} isn't saved.
 */
@Register
public final class ReconstructorCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("reconstructor");

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
        var reconstructor = (Reconstructor)building;
        PayloadBodies.write(reconstructor, out, ids);
        out.f(reconstructor.progress);
        UnitBlockBodies.writeVec(out, reconstructor.commandPos);
        UnitBlockBodies.writeCommand(out, reconstructor.command);
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        var reconstructor = (Reconstructor)building;
        PayloadBodies.read(reconstructor, in, ids);
        if(revision >= 1) reconstructor.progress = in.f();
        if(revision >= 2) reconstructor.commandPos = UnitBlockBodies.readVec(in);
        if(revision >= 3) reconstructor.command = UnitBlockBodies.readCommand(in, reconstructor, ids);
    }

    @Override
    public void skip(Tape in, int revision){
        PayloadBodies.skipBlockBody(in);
        if(revision >= 1) in.f();
        if(revision >= 2){
            in.f();
            in.f();
        }
        if(revision >= 3) in.b();
    }
}
