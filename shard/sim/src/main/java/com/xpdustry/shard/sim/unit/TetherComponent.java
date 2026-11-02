package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.common.registry.*;

/** {@code BuildingTetherComp.update}: a unit despawns once its building is gone or changed team. */
@Register
public final class TetherComponent implements UnitComponent{
    @Override
    public String name(){
        return "BuildingTetherComp";
    }

    @Override
    public boolean applies(UnitParams params){
        return params.tethered();
    }

    @Override
    public void update(Unit unit){
        if(unit.tether == null || !unit.tether.isValid() || unit.tether.team != unit.team) UnitCalls.despawn(unit);
    }
}
