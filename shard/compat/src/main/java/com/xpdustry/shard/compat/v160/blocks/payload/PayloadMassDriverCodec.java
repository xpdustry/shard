package com.xpdustry.shard.compat.v160.blocks.payload;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.payload.*;
import com.xpdustry.shard.sim.building.*;

/** The payload mass drivers' save bytes (PayloadMassDriver.java:487-516), revision 1: the payload block's body, the link, the turret's angle, the state's ordinal, then the reload, the charge, {@code loaded} and {@code charging}. Revision 0 stops after the state. */
@Register
public final class PayloadMassDriverCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("payload-mass-driver");

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
        return revision == 0 || revision == 1;
    }

    @Override
    public void write(Building building, Writes out, ContentHeader.IdMapping ids){
        var driver = (PayloadMassDriver)building;
        PayloadBodies.write(driver, out, ids);
        out.i(driver.link);
        out.f(driver.turretRotation);
        out.b(driver.state.ordinal());
        out.f(driver.reloadCounter);
        out.f(driver.charge);
        out.bool(driver.loaded);
        out.bool(driver.charging);
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        var driver = (PayloadMassDriver)building;
        PayloadBodies.read(driver, in, ids);
        driver.link = in.i();
        driver.turretRotation = in.f();
        driver.state = PayloadMassDriver.PayloadDriverState.all[in.b()];
        if(revision >= 1){
            driver.reloadCounter = in.f();
            driver.charge = in.f();
            driver.loaded = in.bool();
            driver.charging = in.bool();
        }
    }

    @Override
    public void skip(Tape in, int revision){
        PayloadBodies.skipBlockBody(in);
        in.i();
        in.f();
        in.b();
        if(revision >= 1){
            in.f();
            in.f();
            in.bool();
            in.bool();
        }
    }
}
