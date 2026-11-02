package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.common.registry.*;

/** {@code LegsComp.update}, for legged classes: {@link MoveShape.Legs#update}. */
@Register
public final class LegsComponent implements UnitComponent{
    @Override
    public String name(){
        return "LegsComp";
    }

    @Override
    public boolean applies(UnitParams params){
        return params.locomotion() == Locomotion.LEGS;
    }

    @Override
    public void update(Unit unit){
        ((MoveShape.Legs)unit.shape).update(unit);
    }
}
