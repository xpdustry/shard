package com.xpdustry.shard.compat.v160.blocks.sandbox;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.sandbox.*;
import com.xpdustry.shard.sim.building.*;

/**
 * {@code TargetDummyBuild.write}/{@code read}: the unit's id (-1 for none), boosting, armor, reset time, hit size and
 * the unit's team. A read keeps the id for the first update to find the unit by ({@code readUnitId}).
 */
@Register
public final class TargetDummyCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("target-dummy");

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
        var dummy = (TargetDummy)building;
        out.i(dummy.unit == null ? -1 : dummy.unit.id());
        out.bool(dummy.boosting);
        out.f(dummy.unitArmor);
        out.f(dummy.resetTime);
        out.f(dummy.dummySize);
        out.i(dummy.unitTeam);
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        var dummy = (TargetDummy)building;
        dummy.readUnitId = in.i();
        dummy.boosting = in.bool();
        dummy.unitArmor = in.f();
        dummy.resetTime = in.f();
        dummy.dummySize = in.f();
        // Team.get(id)
        dummy.unitTeam = ((byte)in.i()) & 0xff;
    }

    @Override
    public void skip(Tape in, int revision){
        in.i();
        in.bool();
        in.f();
        in.f();
        in.f();
        in.i();
    }
}
