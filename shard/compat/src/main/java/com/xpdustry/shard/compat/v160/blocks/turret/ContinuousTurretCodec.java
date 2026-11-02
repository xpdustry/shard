package com.xpdustry.shard.compat.v160.blocks.turret;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.turret.*;
import com.xpdustry.shard.sim.building.*;

/** {@code ContinuousTurretBuild} (version 3): the turret's reload and aim, then from revision 3 the laser's {@code lastLength}. */
@Register
public final class ContinuousTurretCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("continuous-turret");

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
        var turret = (ContinuousTurret)building;
        TurretBodies.writeTurret(turret, out);
        out.f(turret.lastLength);
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        var turret = (ContinuousTurret)building;
        TurretBodies.readTurret(turret, in, revision);
        if(revision >= 3) turret.lastLength = in.f();
    }

    @Override
    public void skip(Tape in, int revision){
        TurretBodies.skipTurret(in, revision);
        if(revision >= 3) in.f();
    }
}
