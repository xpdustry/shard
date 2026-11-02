package com.xpdustry.shard.sim.blocks.sandbox;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code Accelerator.AcceleratorBuild} (Accelerator.java), the interplanetary accelerator, as a server hosts
 * it off the campaign: it takes its launch core's requirements (and, once built, a core's worth more of each), builds
 * the core over {@code buildDuration} ticks while it has them and power, and never launches ({@code canLaunch()} needs
 * the campaign). Its heat and glow follow its efficiency, as on a client.
 */
@Kind("accelerator")
public final class Accelerator extends Building implements Checkpointable{
    public float heat, statusLerp;
    public float progress;
    public float time, launchHeat;
    /** Only a client's launch sets it. */
    public boolean launching;
    @Derived("the launch core's requirements per item, read on first use")
    private int[] capacities;
    @Derived("the launch core's itemCapacity, read on first use")
    private int coreCapacity;
    @Derived("the block's buildDuration, read on first use")
    private float buildDuration = Float.NaN;

    @Override
    public void updateTile(){
        heat = Mathf.lerpDelta(heat, launching ? 1f : efficiency, 0.05f);
        statusLerp = Mathf.lerpDelta(statusLerp, power.status, 0.05f);
        if(!launching){
            time += 1f * efficiency;
        }else{
            time = Mathf.slerpDelta(time, 0f, 0.4f);
        }
        launchHeat = Mathf.lerpDelta(launchHeat, launching ? 1f : 0f, 0.1f);
        if(efficiency >= 0f){
            progress += 1f * efficiency / buildDuration();
            progress = Math.min(progress, 1f);
        }
    }

    public boolean isCoreBuilt(){
        return progress >= 1f;
    }

    @Override
    public int getMaximumAccepted(int item){
        capacities();
        return capacities[item] + (isCoreBuilt() ? coreCapacity : 0);
    }

    @Override
    public boolean acceptItem(Building source, int item){
        return items.get(item) < getMaximumAccepted(item);
    }

    private void capacities(){
        if(capacities != null) return;
        ContentEpoch content = world().content();
        capacities = new int[content.items()];
        if(!block().baked()) return;
        BlockDef launch = content.block(content.id(ContentType.BLOCK, block().fields().s("launchBlock")));
        Map<String, Object> requirements = launch.fields().map("requirements");
        for(var stack : requirements.entrySet()){
            capacities[content.id(ContentType.ITEM, stack.getKey())] = ((Number)stack.getValue()).intValue();
        }
        coreCapacity = launch.fields().i("itemCapacity");
    }

    private float buildDuration(){
        if(Float.isNaN(buildDuration)) buildDuration = block().baked() ? block().fields().f("buildDuration") : 120f;
        return buildDuration;
    }

    @Override
    public void write(CheckpointOut out){
        out.f("heat", heat).f("statusLerp", statusLerp).f("progress", progress).f("time", time).f("launchHeat", launchHeat)
            .bool("launching", launching);
    }

    @Override
    public void read(CheckpointIn in){
        heat = in.f("heat");
        statusLerp = in.f("statusLerp");
        progress = in.f("progress");
        time = in.f("time");
        launchHeat = in.f("launchHeat");
        launching = in.bool("launching");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (Accelerator)into;
        out.heat = heat;
        out.statusLerp = statusLerp;
        out.progress = progress;
        out.time = time;
        out.launchHeat = launchHeat;
        out.launching = launching;
        out.capacities = capacities;
        out.coreCapacity = coreCapacity;
        out.buildDuration = buildDuration;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.f(heat).f(statusLerp).f(progress).f(time).f(launchHeat).bool(launching);
    }
}
