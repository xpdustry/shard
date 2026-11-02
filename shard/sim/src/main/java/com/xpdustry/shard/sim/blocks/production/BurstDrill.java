package com.xpdustry.shard.sim.blocks.production;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code BurstDrill.BurstDrillBuild} (BurstDrill.java): Erekir's impact and eruption drills. Progress runs
 * at the drill's speed, regardless of how many ore tiles it covers, and a full cycle offloads one item per ore tile
 * at once ({@link #dominantItems} of them), if it has room for all of them. Hardness doesn't slow it.
 *
 * <p>Its shake, sound and drill effect play only where a renderer saw the building ({@code wasVisible}), so nothing
 * here draws from the random. {@link #smoothProgress}, {@link #invertTime} and {@link #timeDrilled} only feed
 * drawing; they are kept because vanilla keeps them.
 */
@Kind("burst-drill")
public final class BurstDrill extends Drill{
    /** Vanilla's comment: used so the lights don't fade out immediately. */
    public float smoothProgress, invertTime;
    @Derived("the block's invertedTime, read on first use")
    private float invertedTime = Float.NaN;

    /** {@code BurstDrill.getDrillTime(item)}: ticks per cycle, whatever the item's hardness. */
    @Override
    public float getDrillTime(int item){
        return own().drillTime() / own().drillMultipliers()[item];
    }

    @Override
    public boolean shouldConsume(){
        return items.total() <= info().itemCapacity - dominantItems && enabled;
    }

    @Override
    public void updateTile(){
        if(dominantItem == -1) return;

        if(invertTime > 0f) invertTime -= delta() / invertedTime();

        if(timer(0, own().dumpTime() / timeScale)){
            dump(items.has(dominantItem) ? dominantItem : -1);
        }

        float drillTime = getDrillTime(dominantItem);
        int capacity = info().itemCapacity;

        smoothProgress = Mathf.lerpDelta(smoothProgress, progress / (drillTime - 20f), 0.1f);

        if(items.total() <= capacity - dominantItems && dominantItems > 0 && efficiency > 0){
            warmup = Mathf.approachDelta(warmup, progress / drillTime, 0.01f);

            float speed = Mathf.lerp(1f, own().liquidBoostIntensity(), optionalEfficiency) * efficiency;

            // speedCurve is Interp.pow2In for every vanilla burst drill
            timeDrilled += Interp.pow2In.apply(progress / drillTime) * speed;

            lastDrillSpeed = 1f / drillTime * speed * dominantItems;
            progress += delta() * speed;
        }else{
            warmup = Mathf.approachDelta(warmup, 0f, 0.01f);
            lastDrillSpeed = 0f;
            return;
        }

        if(dominantItems > 0 && progress >= drillTime && items.total() < capacity){
            for(int i = 0; i < dominantItems; i++) offload(dominantItem);
            invertTime = 1f;
            progress %= drillTime;
        }
    }

    private float invertedTime(){
        if(Float.isNaN(invertedTime)){
            if(block().baked() && !"interp:pow2In".equals(block().fields().s("speedCurve"))){
                throw new IllegalStateException(block().name() + ": speed curve " + block().fields().s("speedCurve") + " is not ported");
            }
            invertedTime = block().baked() ? block().fields().f("invertedTime") : 200f;
        }
        return invertedTime;
    }

    @Override
    public void write(CheckpointOut out){
        super.write(out);
        out.f("smoothProgress", smoothProgress).f("invertTime", invertTime);
    }

    @Override
    public void read(CheckpointIn in){
        super.read(in);
        smoothProgress = in.f("smoothProgress");
        invertTime = in.f("invertTime");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (BurstDrill)into;
        out.smoothProgress = smoothProgress;
        out.invertTime = invertTime;
        out.invertedTime = invertedTime;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.f(smoothProgress).f(invertTime);
    }
}
