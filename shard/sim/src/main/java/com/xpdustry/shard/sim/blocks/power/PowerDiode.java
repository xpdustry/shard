package com.xpdustry.shard.sim.blocks.power;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.sim.building.*;

/**
 * Vanilla's {@code PowerDiode.PowerDiodeBuild}: an insulated block that moves stored power from the graph behind it to
 * the graph in front, never back. When the back's batteries are fuller, it sends half of what would even out both
 * sides' fill, no more than the front can take, outside the solve ({@link PowerGraph#transferPower}). It isn't in
 * either graph, and a disabled diode does nothing ({@code noUpdateDisabled}).
 */
@Kind("power-diode")
public final class PowerDiode extends Building{
    @Override
    public void updateTile(){
        Building front = front(), back = back();
        if(front == null || back == null || !back.block().hasPower() || !front.block().hasPower() || back.team != team || front.team != team) return;

        PowerGraph backGraph = back.power.graph;
        PowerGraph frontGraph = front.power.graph;
        if(backGraph == frontGraph) return;

        float backStored = backGraph.getBatteryStored();
        float backCapacity = backGraph.getTotalBatteryCapacity();
        float frontStored = frontGraph.getBatteryStored();
        float frontCapacity = frontGraph.getTotalBatteryCapacity();

        if(backStored / backCapacity <= frontStored / frontCapacity) return;

        float targetPercentage = (frontStored + backStored) / (frontCapacity + backCapacity);
        // send half of the difference
        float amount = (targetPercentage * frontCapacity - frontStored) / 2;
        // no more than the front can take
        amount = Mathf.clamp(amount, 0, frontCapacity - frontStored);

        backGraph.transferPower(-amount);
        frontGraph.transferPower(amount);
    }
}
