package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.common.registry.*;

/** {@code ItemsComp.update}: the stack never holds more than the type's capacity. */
@Register
public final class ItemsComponent implements UnitComponent{
    @Override
    public String name(){
        return "ItemsComp";
    }

    @Override
    public boolean applies(UnitParams params){
        return true;
    }

    @Override
    public void update(Unit unit){
        unit.stackAmount = Mathf.clamp(unit.stackAmount, 0, unit.params().itemCapacity());
    }
}
