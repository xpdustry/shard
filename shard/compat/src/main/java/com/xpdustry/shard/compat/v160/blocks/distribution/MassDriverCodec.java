package com.xpdustry.shard.compat.v160.blocks.distribution;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.distribution.*;
import com.xpdustry.shard.sim.building.*;

/**
 * The mass driver's save and sync bytes (MassDriver.java:363), revision 0: the link, the turret's angle and the
 * state's ordinal as a byte. A state byte outside the three throws, as vanilla's {@code DriverState.all[b]} does.
 */
@Register
public final class MassDriverCodec implements BuildingCodec{
    private static final KindKey KIND = KindKey.of("mass-driver");

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
        var driver = (MassDriver)building;
        out.i(driver.link);
        out.f(driver.driverRotation);
        out.b(driver.state.ordinal());
    }

    @Override
    public void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids){
        var driver = (MassDriver)building;
        driver.link = in.i();
        driver.driverRotation = in.f();
        driver.state = MassDriver.DriverState.all[in.b()];
    }

    @Override
    public void skip(Tape in, int revision){
        in.i();
        in.f();
        in.b();
    }
}
