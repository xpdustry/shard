package com.xpdustry.shard.sim.blocks.turret;

import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.building.*;

/**
 * Vanilla's {@code PowerTurret.PowerTurretBuild}: one bullet ({@code shootType}), always loaded. It can always turn;
 * power reaches its shots through {@link #efficiency}, which scales the reload ({@code ConsumePower}).
 */
@Kind("power-turret")
public final class PowerTurret extends TurretBuild{
    @Override
    public Ammo useAmmo(){
        // nothing used directly
        return type().shootType;
    }

    /** You can always rotate, but never shoot if there's no power. */
    @Override
    public boolean hasAmmo(){
        return true;
    }

    @Override
    public Ammo peekAmmo(){
        return type().shootType;
    }

    @Override
    public double sense(LAccess sensor){
        return switch(sensor){
            case ammo -> power == null ? 0f : power.status;
            case ammoCapacity -> 1;
            case heat -> type().heatRequirement > 0 ? heatReq : Float.NaN;
            default -> super.sense(sensor);
        };
    }
}
