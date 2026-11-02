package com.xpdustry.shard.compat.v160.blocks.turret;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.turret.*;
import com.xpdustry.shard.sim.building.*;

/** {@code TractorBeamBuild.write}/{@code read}: the aim ({@code rotation}). */
@Register
public final class TractorBeamTurretCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("tractor-beam-turret");

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
        var tractorBeamTurret = (TractorBeamTurret)building;
        out.f(tractorBeamTurret.turretRotation);
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        var tractorBeamTurret = (TractorBeamTurret)building;
        tractorBeamTurret.turretRotation = in.f();
    }

    @Override
    public void skip(Tape in, int revision){
        in.f();
    }
}
