package com.xpdustry.shard.sim.blocks.power;

import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code PowerSource.PowerSourceBuild} (sandbox): a {@link LaserNode} that produces its block's
 * {@code powerProduction} while enabled, and turns itself off where it may not update.
 */
@Kind("power-source")
public final class PowerSource extends LaserNode{
    @Derived("the block's powerProduction, read on first use")
    private float powerProduction = Float.NaN;

    @Override
    public void onProximityUpdate(){
        super.onProximityUpdate();
        if(!allowUpdate()) enabled = false;
    }

    @Override
    public float getPowerProduction(){
        if(Float.isNaN(powerProduction)) powerProduction = block().baked() ? block().fields().f("powerProduction") : 10000f;
        return enabled ? powerProduction : 0f;
    }

    @Override
    protected void copyState(com.xpdustry.shard.sim.entity.Entity into, CopyContext context){
        super.copyState(into, context);
        ((PowerSource)into).powerProduction = powerProduction;
    }
}
