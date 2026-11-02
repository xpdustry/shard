package com.xpdustry.shard.compat.v160.blocks.units;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.units.*;
import com.xpdustry.shard.sim.building.*;

/** {@code RepairPointBuild} (version 1): the aim ({@code rotation}); revision 0 has none. */
@Register
public final class RepairTurretCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("repair-turret");

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
        var repairTurret = (RepairTurret)building;
        out.f(repairTurret.turretRotation);
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        var repairTurret = (RepairTurret)building;
        if(revision >= 1){
            repairTurret.turretRotation = in.f();
        }
    }

    @Override
    public void skip(Tape in, int revision){
        if(revision >= 1){
            in.f();
        }
    }
}
