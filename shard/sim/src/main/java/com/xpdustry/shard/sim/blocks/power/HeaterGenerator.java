package com.xpdustry.shard.sim.blocks.power;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.blocks.heat.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code HeaterGenerator.HeaterGeneratorBuild} (HeaterGenerator.java:48), the neoplasia reactor: a consume
 * generator whose {@code heat} approaches {@code heatOutput × efficiency} by {@code warmupRate} a tick, whatever the
 * efficiency, and goes out of its front to the {@link HeatConsumer} it faces.
 */
@Kind("heater-generator")
public final class HeaterGenerator extends ConsumeGenerator implements HeatBlock{
    public float heat;

    private float field(String name, float fallback){
        return block().baked() ? block().fields().f(name) : fallback;
    }

    @Override
    public void updateTile(){
        super.updateTile();
        // heat approaches target at the same speed regardless of efficiency
        heat = Mathf.approachDelta(heat, field("heatOutput", 10f) * efficiency, field("warmupRate", 0.15f) * delta());
    }

    @Override
    public float heat(){
        return heat;
    }

    @Override
    public float heatFrac(){
        return heat / field("heatOutput", 10f);
    }

    /** {@code sensor @heat}. */
    @Override
    public double sense(LAccess sensor){
        if(sensor == LAccess.heat) return heat;
        return super.sense(sensor);
    }

    @Override
    public void write(CheckpointOut out){
        super.write(out);
        out.f("heat", heat);
    }

    @Override
    public void read(CheckpointIn in){
        super.read(in);
        heat = in.f("heat");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        ((HeaterGenerator)into).heat = heat;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.f(heat);
    }
}
