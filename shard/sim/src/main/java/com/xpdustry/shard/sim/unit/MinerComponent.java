package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.common.registry.*;

/**
 * {@code MinerComp.update}, in every unit class (after {@code ItemsComp}, and after {@code MechComp} in a mech):
 * {@link Miner#update}. A unit with no mine tile returns at once, as vanilla's first line does.
 */
@Register
public final class MinerComponent implements UnitComponent{
    @Override
    public String name(){
        return "MinerComp";
    }

    @Override
    public boolean applies(UnitParams params){
        return true;
    }

    @Override
    public void update(Unit unit){
        Miner.update(unit);
    }
}
