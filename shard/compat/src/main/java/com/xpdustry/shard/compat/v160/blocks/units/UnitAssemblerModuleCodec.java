package com.xpdustry.shard.compat.v160.blocks.units;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.blocks.payload.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.units.*;
import com.xpdustry.shard.sim.building.*;

/** The assembler module's save bytes (UnitAssemblerModule.java), revision 0: the payload block's body only. */
@Register
public final class UnitAssemblerModuleCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("unit-assembler-module");

    @Override
    public KindKey kind(){
        return KIND;
    }

    @Override
    public int revision(){
        return 0;
    }

    @Override
    public boolean reads(int revision){
        return revision == 0;
    }

    @Override
    public void write(Building building, Writes out, ContentHeader.IdMapping ids){
        PayloadBodies.write((UnitAssemblerModule)building, out, ids);
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        PayloadBodies.read((UnitAssemblerModule)building, in, ids);
    }

    @Override
    public void skip(Tape in, int revision){
        PayloadBodies.skipBlockBody(in);
    }
}
