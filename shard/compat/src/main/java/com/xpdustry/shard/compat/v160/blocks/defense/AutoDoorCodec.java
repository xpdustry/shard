package com.xpdustry.shard.compat.v160.blocks.defense;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.defense.*;
import com.xpdustry.shard.sim.building.*;

/** {@code AutoDoorBuild.write}/{@code read}: whether it is open. */
@Register
public final class AutoDoorCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("auto-door");

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
        out.bool(((AutoDoor)building).open);
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        ((AutoDoor)building).open = in.bool();
    }

    @Override
    public void skip(Tape in, int revision){
        in.bool();
    }
}
