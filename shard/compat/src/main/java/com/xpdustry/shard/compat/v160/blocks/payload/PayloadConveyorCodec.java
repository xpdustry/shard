package com.xpdustry.shard.compat.v160.blocks.payload;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.payload.*;
import com.xpdustry.shard.sim.building.*;

/**
 * The payload conveyor's save bytes (PayloadConveyor.java:298-315), revision 0: {@code progress}, {@code itemRotation},
 * then the item as {@code Payload.write}. The router's codec writes the same first.
 */
@Register
public final class PayloadConveyorCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("payload-conveyor");

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
        writeConveyor((PayloadConveyorBuild)building, out, ids);
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        readConveyor((PayloadConveyorBuild)building, in, ids);
    }

    @Override
    public void skip(Tape in, int revision){
        skipConveyor(in);
    }

    static void writeConveyor(PayloadConveyorBuild conveyor, Writes out, ContentHeader.IdMapping ids){
        out.f(conveyor.progress);
        out.f(conveyor.itemRotation);
        PayloadBodies.write(conveyor.world(), conveyor.item, out, ids);
    }

    static void readConveyor(PayloadConveyorBuild conveyor, Reads in, ContentHeader.IdMapping ids){
        // for derelicts
        conveyor.progress = in.f();
        conveyor.itemRotation = in.f();
        conveyor.item = PayloadBodies.read(conveyor.world(), in, ids);
    }

    static void skipConveyor(Tape in){
        in.f();
        in.f();
        PayloadBodies.skip(in);
    }
}
