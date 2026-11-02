package com.xpdustry.shard.sim.blocks.production;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code Fracker.FrackerBuild} (Fracker.java): the oil extractor. A {@link SolidPump} that also burns its
 * items: whenever {@link #accumulator}, which grows by its efficiency each tick it works, reaches
 * {@code itemUseTime}, it consumes one batch (sand) first. Idle, it cools down and still dumps.
 */
@Kind("fracker")
public final class Fracker extends SolidPump{
    public float accumulator;
    @Derived("the block's itemUseTime, read on first use")
    private float itemUseTime = Float.NaN;

    @Override
    public void updateTile(){
        if(efficiency > 0){
            if(accumulator >= itemUseTime()){
                consume();
                accumulator -= itemUseTime();
            }

            super.updateTile();
            accumulator += delta() * efficiency;
        }else{
            warmup = Mathf.lerpDelta(warmup, 0f, 0.02f);
            lastPump = 0f;
            dumpLiquid(solid().result());
        }
    }

    private float itemUseTime(){
        if(Float.isNaN(itemUseTime)) itemUseTime = block().baked() ? block().fields().f("itemUseTime") : 100f;
        return itemUseTime;
    }

    @Override
    public void write(CheckpointOut out){
        super.write(out);
        out.f("accumulator", accumulator);
    }

    @Override
    public void read(CheckpointIn in){
        super.read(in);
        accumulator = in.f("accumulator");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (Fracker)into;
        out.accumulator = accumulator;
        out.itemUseTime = itemUseTime;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.f(accumulator);
    }
}
