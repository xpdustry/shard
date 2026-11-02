package com.xpdustry.shard.compat.v160.blocks.turret;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.turret.*;
import com.xpdustry.shard.sim.building.*;

/** {@code LaserTurretBuild} (version 1, {@code TurretBuild}'s): the turret's reload and aim; the held laser isn't saved. */
@Register
public final class LaserTurretCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("laser-turret");

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
        var turret = (LaserTurret)building;
        TurretBodies.writeTurret(turret, out);
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        var turret = (LaserTurret)building;
        TurretBodies.readTurret(turret, in, revision);
    }

    @Override
    public void skip(Tape in, int revision){
        TurretBodies.skipTurret(in, revision);
    }
}
