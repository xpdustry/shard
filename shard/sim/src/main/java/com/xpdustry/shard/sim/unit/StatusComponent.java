package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.common.registry.*;

/**
 * {@code StatusComp.update} (WP42): every unit class has it, between {@code ShieldComp} and {@code SyncComp}. The body
 * is {@link Statuses#update(Unit)}.
 */
@Register
public final class StatusComponent implements UnitComponent{
    @Override
    public String name(){
        return "StatusComp";
    }

    @Override
    public boolean applies(UnitParams params){
        return true;
    }

    @Override
    public void update(Unit unit){
        unit.statuses.update(unit);
    }
}
