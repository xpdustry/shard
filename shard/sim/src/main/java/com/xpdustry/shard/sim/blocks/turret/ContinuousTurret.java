package com.xpdustry.shard.sim.blocks.turret;

import com.xpdustry.shard.sim.building.*;

/** Vanilla's {@code ContinuousTurret.ContinuousTurretBuild} with its own {@code shootType}: lustre, sublimate's kin. */
@Kind("continuous-turret")
public final class ContinuousTurret extends ContinuousTurretBuild{
    @Override
    public Ammo peekAmmo(){
        return type().shootType;
    }
}
