package com.xpdustry.shard.sim.blocks.defense;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code Radar.RadarBuild} (Radar.java): its fog radius grows to {@code fogRadius} over
 * {@code discoveryTime} ticks of efficiency, scaled by its smoothed efficiency.
 *
 * <p>When the radius moves by half a tile its team's fog is redrawn ({@code fogControl.forceUpdate}). Saved:
 * {@link #progress}.
 */
@Kind("radar")
public final class Radar extends Building implements Checkpointable{
    public float progress, lastRadius, smoothEfficiency = 1f, totalProgress;

    @Derived("the block's parameters, read on first use")
    private Params params;

    record Params(float discoveryTime, int fogRadius){
        static Params of(BlockDef block){
            if(!block.baked()) return new Params(60f * 10f, 10);
            var f = block.fields();
            return new Params(f.f("discoveryTime"), f.i("fogRadius"));
        }
    }

    Params params(){
        if(params == null) params = Params.of(block());
        return params;
    }

    /** {@code fogRadius()}: in tiles. */
    @Override
    public float fogRadius(){
        return params().fogRadius * progress * smoothEfficiency;
    }

    @Override
    public void updateTile(){
        smoothEfficiency = Mathf.lerpDelta(smoothEfficiency, efficiency, 0.05f);
        if(Math.abs(fogRadius() - lastRadius) >= 0.5f){
            com.xpdustry.shard.sim.fog.FogControl.forceUpdate(this);
            lastRadius = fogRadius();
        }
        progress += edelta() / params().discoveryTime;
        progress = Mathf.clamp(progress);
        totalProgress += efficiency * edelta();
    }

    public float progress(){
        return progress;
    }

    /** {@code canPickup()}: no payload unit lifts a radar (Radar.java:75). */
    @Override
    public boolean canPickup(){
        return false;
    }

    @Override
    public void write(CheckpointOut out){
        out.f("progress", progress).f("lastRadius", lastRadius).f("smoothEfficiency", smoothEfficiency).f("totalProgress", totalProgress);
    }

    @Override
    public void read(CheckpointIn in){
        progress = in.f("progress");
        lastRadius = in.f("lastRadius");
        smoothEfficiency = in.f("smoothEfficiency");
        totalProgress = in.f("totalProgress");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (Radar)into;
        out.progress = progress;
        out.lastRadius = lastRadius;
        out.smoothEfficiency = smoothEfficiency;
        out.totalProgress = totalProgress;
        out.params = params;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.f(progress).f(lastRadius).f(smoothEfficiency).f(totalProgress);
    }
}
