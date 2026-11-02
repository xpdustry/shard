package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.building.*;

/**
 * {@code TargetDummyComp.update}: a target dummy despawns once its building is gone, whatever its team. Its building
 * is {@link Unit#tether}. A dummy whose building a payload carries ({@link Building#isPayload()}) stays.
 */
@Register
public final class DummyComponent implements UnitComponent{
    @Override
    public String name(){
        return "TargetDummyComp";
    }

    @Override
    public boolean applies(UnitParams params){
        return params.dummy();
    }

    @Override
    public void update(Unit unit){
        if(unit.tether == null || !unit.tether.isPayload() && !unit.tether.isValid()) UnitCalls.despawn(unit);
    }
}
