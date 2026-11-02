package com.xpdustry.shard.sim.blocks.payload;

import com.xpdustry.shard.sim.building.*;

/**
 * Vanilla's {@code PayloadLoader}: everything is in {@link PayloadLoaderBuild}. Its power use is dynamic
 * ({@code loadPowerDynamic}, PayloadLoader.java:82): nothing while empty, its base usage while it holds a payload, and
 * {@code maxPowerConsumption} more while it charges a battery.
 */
@Kind("payload-loader")
public final class PayloadLoader extends PayloadLoaderBuild{
    /** {@code ConsumePowerDynamic}'s lambda. */
    @Override
    public float powerUse(){
        LoaderParams params = params();
        if(!shouldConsume()) return 0f;
        return hasBattery() && !exporting ? params.maxPowerConsumption() + params.basePowerUse() : params.basePowerUse();
    }
}
