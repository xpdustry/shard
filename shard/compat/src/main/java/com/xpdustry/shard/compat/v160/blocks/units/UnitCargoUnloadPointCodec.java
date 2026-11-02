package com.xpdustry.shard.compat.v160.blocks.units;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.ContentType;
import com.xpdustry.shard.sim.blocks.units.*;
import com.xpdustry.shard.sim.building.*;

/**
 * {@code UnitCargoUnloadPointBuild.write}/{@code read} (UnitCargoUnloadPoint.java:95): the item's id as a short (-1 for
 * none), then {@code stale}. The stale timer isn't saved: it starts over.
 */
@Register
public final class UnitCargoUnloadPointCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("unit-cargo-unload-point");

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
        var point = (UnitCargoUnloadPoint)building;
        out.s(point.item == -1 ? -1 : ids.requireStream(ContentType.ITEM, point.item));
        out.bool(point.stale);
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        var point = (UnitCargoUnloadPoint)building;
        point.item = ids.toCurrent(ContentType.ITEM, in.s());
        point.stale = in.bool();
    }

    @Override
    public void skip(Tape in, int revision){
        in.s();
        in.bool();
    }
}
