package com.xpdustry.shard.compat.v160.blocks.units;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.units.*;
import com.xpdustry.shard.sim.building.*;

/**
 * {@code UnitTransportSourceBuild.write}/{@code read} (UnitCargoLoader.java:174): the unit's id, -1 for none. A read
 * keeps the id for the first update to find the unit by ({@code readUnitId}).
 */
@Register
public final class UnitCargoLoaderCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("unit-cargo-loader");

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
        var loader = (UnitCargoLoader)building;
        out.i(loader.unit == null ? -1 : loader.unit.id());
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        ((UnitCargoLoader)building).readUnitId = in.i();
    }

    @Override
    public void skip(Tape in, int revision){
        in.i();
    }
}
