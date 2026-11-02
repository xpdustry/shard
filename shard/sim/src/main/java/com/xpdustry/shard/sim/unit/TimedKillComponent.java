package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.common.registry.*;

/** {@code TimedKillComp.update} (priority 100, last): a missile dies when its lifetime runs out. */
@Register
public final class TimedKillComponent implements UnitComponent{
    @Override
    public String name(){
        return "TimedKillComp";
    }

    @Override
    public float priority(){
        return 100f;
    }

    @Override
    public boolean applies(UnitParams params){
        return params.timedKill();
    }

    @Override
    public void update(Unit unit){
        unit.time = Math.min(unit.time + 1f, unit.lifetime);
        if(unit.time >= unit.lifetime) unit.kill();
    }
}
