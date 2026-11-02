package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.common.registry.*;

/**
 * {@code PayloadComp.update()} ({@link Payloads#update}), in the merged update of the classes that carry payloads
 * ({@code PayloadUnit}, {@code BuildingTetherPayloadUnit}): between {@code MinerComp} and {@code ShieldComp}.
 */
@Register
public final class PayloadComponent implements UnitComponent{
    @Override
    public String name(){
        return "PayloadComp";
    }

    @Override
    public boolean applies(UnitParams params){
        return params.payloads();
    }

    @Override
    public void update(Unit unit){
        Payloads.update(unit);
    }
}
