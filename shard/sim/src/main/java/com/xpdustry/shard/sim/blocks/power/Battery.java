package com.xpdustry.shard.sim.blocks.power;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.sim.building.*;

/**
 * Vanilla's {@code Battery.BatteryBuild}: the battery and the large battery. A battery doesn't update; its graph
 * charges and drains its {@code power.status}, the stored fraction of its buffered consumer's capacity.
 */
@Kind("battery")
public final class Battery extends Building{
    /**
     * {@code overwrote(previous)} (Battery.java:89): built over batteries or other buffered blocks, it takes their
     * stored power, as a fraction of its own capacity.
     */
    @Override
    public void overwrote(java.util.List<Building> previous){
        float capacity = info().consumers.power().capacity;
        for(Building other : previous){
            var consumed = other.info().consumers.power();
            if(other.power != null && consumed != null && consumed.buffered){
                float amount = consumed.capacity * other.power.status;
                power.status = Mathf.clamp(power.status + amount / capacity);
            }
        }
    }

    /** Empty, full or in between, as the stored fraction says. */
    @Override
    public BlockStatus status(double stateTick){
        if(Mathf.equal(power.status, 0f, 0.001f)) return BlockStatus.noInput;
        if(Mathf.equal(power.status, 1f, 0.001f)) return BlockStatus.active;
        return BlockStatus.noOutput;
    }
}
