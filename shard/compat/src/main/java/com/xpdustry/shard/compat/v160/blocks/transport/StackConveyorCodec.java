package com.xpdustry.shard.compat.v160.blocks.transport;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.transport.*;
import com.xpdustry.shard.sim.building.*;

/**
 * {@code StackConveyorBuild.write}/{@code read} (StackConveyor.java:389): the link as an int and the cooldown. The
 * state isn't saved: the map's proximity update sets it, and the moving item is the first one held.
 */
@Register
public final class StackConveyorCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("stack-conveyor");

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
        var stack = (StackConveyor)building;
        out.i(stack.link);
        out.f(stack.cooldown);
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        var stack = (StackConveyor)building;
        stack.link = in.i();
        stack.cooldown = in.f();
        stack.loaded();
    }

    @Override
    public void skip(Tape in, int revision){
        in.i();
        in.f();
    }
}
