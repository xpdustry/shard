package com.xpdustry.shard.compat.v160.blocks.turret;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.turret.*;
import com.xpdustry.shard.sim.building.*;

/**
 * The turret family's save bytes, shared by its codecs: {@code TurretBuild.write} (revision 1: {@code reloadCounter},
 * then {@code rotation}), {@code ItemTurretBuild}'s ammo entries (revision 2) and {@code ContinuousTurretBuild}'s
 * {@code lastLength} (revision 3). And {@link #readSync}, the snapshot read whose quirk keeps the turret's aim.
 */
public final class TurretBodies{
    private TurretBodies(){
    }

    /** {@code TurretBuild.write}. */
    static void writeTurret(TurretBuild turret, Writes out){
        out.f(turret.reloadCounter);
        out.f(turret.turretRotation);
    }

    /** {@code TurretBuild.read}: nothing before revision 1. */
    static void readTurret(TurretBuild turret, Reads in, int revision){
        if(revision >= 1){
            turret.reloadCounter = in.f();
            turret.turretRotation = in.f();
        }
    }

    static void skipTurret(Tape in, int revision){
        if(revision >= 1){
            in.f();
            in.f();
        }
    }

    /** {@code ItemTurretBuild.write}: the entries in list order, each its item's stream id and amount, as shorts. */
    static void writeAmmo(TurretBuild turret, Writes out, ContentHeader.IdMapping ids){
        out.b(turret.ammoSize);
        for(int i = 0; i < turret.ammoSize; i++){
            out.s(ids.requireStream(ContentType.ITEM, turret.ammoIds[i]));
            out.s(turret.ammoAmounts[i]);
        }
    }

    /** {@code ItemTurretBuild.read}: item ids were bytes before revision 2; unknown items are dropped, amounts capped. */
    static void readAmmo(ItemTurret turret, Reads in, int revision, ContentHeader.IdMapping ids){
        int amount = in.ub();
        int[] items = new int[amount], amounts = new int[amount];
        for(int i = 0; i < amount; i++){
            int stream = revision < 2 ? in.ub() : in.s();
            items[i] = ids.known(ContentType.ITEM, stream) ? ids.toCurrent(ContentType.ITEM, stream) : -1;
            amounts[i] = in.s();
        }
        turret.readAmmo(items, amounts);
    }

    static void skipAmmo(Tape in, int revision){
        int amount = in.ub();
        for(int i = 0; i < amount; i++){
            if(revision < 2) in.ub();
            else in.s();
            in.s();
        }
    }

    /**
     * {@code TurretBuild.readSync} (Turret.java:863): a block snapshot's {@code readAll} (the base, then the kind's body
     * at {@code revision}), keeping the turret's aim and reload. Vanilla quirk kept on purpose: "so clients don't see
     * turrets snapping around", the float {@code rotation} and {@code reloadCounter} survive the read, while the base's
     * 0-3 rotation byte and everything else take the snapshot's values.
     */
    public static void readSync(BuildingCodecs codecs, TurretBuild turret, Reads in, int revision, ContentHeader.IdMapping ids){
        float oldRot = turret.turretRotation, oldReload = turret.reloadCounter;
        BuildingBase.Header header = BuildingBase.header(in);
        BuildingBase.read(turret, header, in, ids, pos -> turret.world().buildings().at(Point2.x(pos), Point2.y(pos)));
        BuildingCodec codec = codecs.get(turret.world().kinds().key(turret.block()));
        codec.read(turret, in, revision, ids);
        turret.turretRotation = oldRot;
        turret.reloadCounter = oldReload;
    }
}
