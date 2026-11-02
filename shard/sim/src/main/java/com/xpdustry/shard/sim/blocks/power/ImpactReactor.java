package com.xpdustry.shard.sim.blocks.power;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code ImpactReactor.ImpactReactorBuild}: the impact reactor. Fully supplied and fully powered, it warms up
 * slowly and consumes a batch every {@code itemDuration}; otherwise it cools faster. Its output is
 * {@code warmup⁵}. It is both producer and consumer of its own graph. It explodes from {@code explosionMinWarmup}.
 *
 * <p>Saved: {@link #warmup}. Vanilla's {@code Trigger.impactPower} is campaign achievements only.
 */
@Kind("impact-reactor")
public final class ImpactReactor extends Generator{
    public float warmup, totalProgress;
    @Derived("the block's warmup speed, item duration and timer, read on first use")
    private Params params;

    record Params(float warmupSpeed, float itemDuration, int timerUse){
        static Params of(Building building){
            if(!building.block().baked()) return new Params(0.001f, 60f, 1);
            var fields = building.block().fields();
            return new Params(fields.f("warmupSpeed"), fields.f("itemDuration"), fields.i("timerUse"));
        }
    }

    private Params own(){
        if(params == null) params = Params.of(this);
        return params;
    }

    @Override
    public void updateTile(){
        Params params = own();
        if(efficiency >= 0.9999f && power.status >= 0.99f){
            warmup = Mathf.lerpDelta(warmup, 1f, params.warmupSpeed() * timeScale);
            if(Mathf.equal(warmup, 1f, 0.001f)) warmup = 1f;
            if(timer(params.timerUse(), params.itemDuration() / timeScale)) consume();
        }else{
            warmup = Mathf.lerpDelta(warmup, 0f, 0.01f);
        }
        totalProgress += warmup;
        productionEfficiency = Mathf.pow(warmup, 5f);
    }

    @Override
    public float warmup(){
        return warmup;
    }

    /** {@code sensor @heat}: the warmup (ImpactReactor.java:128). */
    @Override
    public double sense(LAccess sensor){
        if(sensor == LAccess.heat) return warmup;
        return super.sense(sensor);
    }

    @Override
    public void write(CheckpointOut out){
        super.write(out);
        out.f("warmup", warmup).f("totalProgress", totalProgress);
    }

    @Override
    public void read(CheckpointIn in){
        super.read(in);
        warmup = in.f("warmup");
        totalProgress = in.f("totalProgress");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (ImpactReactor)into;
        out.warmup = warmup;
        out.totalProgress = totalProgress;
        out.params = params;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.f(warmup).f(totalProgress);
    }
}
