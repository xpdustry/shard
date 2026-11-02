package com.xpdustry.shard.compat.v160.blocks.turret;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.turret.*;
import com.xpdustry.shard.sim.building.*;

/** {@code ItemTurretBuild} (version 2): the turret's reload and aim, then its ammo entries. Revision 1 wrote item ids as bytes; revision 0 had no turret fields. */
@Register
public final class ItemTurretCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("item-turret");

    @Override
    public KindKey kind(){
        return KIND;
    }

    @Override
    public int revision(){
        return 2;
    }

    @Override
    public boolean reads(int revision){
        return revision >= 0 && revision <= 2;
    }

    @Override
    public void write(Building building, Writes out, ContentHeader.IdMapping ids){
        var turret = (ItemTurret)building;
        TurretBodies.writeTurret(turret, out);
        TurretBodies.writeAmmo(turret, out, ids);
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        var turret = (ItemTurret)building;
        TurretBodies.readTurret(turret, in, revision);
        TurretBodies.readAmmo(turret, in, revision, ids);
    }

    @Override
    public void skip(Tape in, int revision){
        TurretBodies.skipTurret(in, revision);
        TurretBodies.skipAmmo(in, revision);
    }
}
