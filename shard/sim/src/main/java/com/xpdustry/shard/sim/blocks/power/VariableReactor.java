package com.xpdustry.shard.sim.blocks.power;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.blocks.heat.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code VariableReactor.VariableReactorBuild} (VariableReactor.java:74), the flux reactor: it makes power in
 * proportion to the heat it takes in ({@link Heat#calculate}, up to {@code maxHeat}), and needs coolant (cyanogen) in
 * the same proportion. Its consumers' efficiency is how much coolant it has; {@link #updateEfficiencyMultiplier} turns
 * that into how much of the need is met, moves {@link #instability} toward 0 when all of it is and toward 1 by how
 * much isn't, then scales the efficiency by the heat. At an instability of 1 it destroys itself, and with any heat it
 * explodes when destroyed ({@link #shouldExplode}). Its vapour effect draws its chance from the world's random every
 * update, scaled by {@link #warmup}.
 *
 * <p>Its heat comes from the consumers' order: the efficiency it reads in a tick is from the heat of the tick before,
 * since {@code updateConsumption} runs before {@code updateTile}. Vanilla saves {@code heat}, {@code instability} and
 * {@code warmup}, not {@code totalProgress} or the side heat.
 */
@Kind("variable-reactor")
public final class VariableReactor extends Generator implements HeatConsumer{
    public float[] sideHeat = new float[4];
    public float heat, instability, totalProgress, warmup;
    @Derived("the block's reactor parameters, read on first use")
    private Params params;

    /** {@code VariableReactor}'s fields. */
    record Params(float maxHeat, float unstableSpeed, float warmupSpeed, float effectChance){
        static Params of(Building building){
            if(!building.block().baked()) return new Params(100f, 1f / 60f / 3f, 0.1f, 0.05f);
            var fields = building.block().fields();
            return new Params(fields.f("maxHeat"), fields.f("unstableSpeed"), fields.f("warmupSpeed"), fields.f("effectChance"));
        }
    }

    private Params own(){
        if(params == null) params = Params.of(this);
        return params;
    }

    @Override
    public void updateTile(){
        Params params = own();
        heat = Heat.calculate(this, sideHeat);
        productionEfficiency = efficiency;
        warmup = Mathf.lerpDelta(warmup, productionEfficiency > 0 ? 1f : 0f, params.warmupSpeed());
        // vanilla goes on with the update after the kill: the progress and the effect's draw below still happen
        if(instability >= 1f) kill();
        totalProgress += productionEfficiency;
        // the vapour effect's chance
        Mathf.chanceDelta(world().rand(), params.effectChance() * warmup);
    }

    @Override
    public boolean shouldExplode(){
        return heat > 0f;
    }

    @Override
    public float warmup(){
        return warmup;
    }

    @Override
    public void updateEfficiencyMultiplier(){
        Params params = own();
        // at this stage efficiency is how much coolant is provided; the target is the heat's share
        float target = Mathf.clamp(heat / params.maxHeat());
        // the fraction of the coolant needed that is provided
        float efficiencyMet = Mathf.clamp(Mathf.zero(target) ? 1f : efficiency / target);
        boolean met = efficiencyMet >= 0.99999f;
        // met: instability falls at half speed; not met: it rises by how much isn't
        instability = Mathf.approachDelta(instability, met ? 0f : 1f, met ? 0.5f : params.unstableSpeed() * (1f - efficiencyMet));
        // now scale efficiency by the target, so it consumes less with less heat
        efficiency *= target;
    }

    @Override
    public float[] sideHeat(){
        return sideHeat;
    }

    @Override
    public float heatRequirement(){
        return own().maxHeat();
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
        out.floats("sideHeat", sideHeat).f("heat", heat).f("instability", instability).f("totalProgress", totalProgress).f("warmup", warmup);
    }

    @Override
    public void read(CheckpointIn in){
        super.read(in);
        in.floats("sideHeat", sideHeat);
        heat = in.f("heat");
        instability = in.f("instability");
        totalProgress = in.f("totalProgress");
        warmup = in.f("warmup");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (VariableReactor)into;
        out.sideHeat = sideHeat.clone();
        out.heat = heat;
        out.instability = instability;
        out.totalProgress = totalProgress;
        out.warmup = warmup;
        out.params = params;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.floats(sideHeat).f(heat).f(instability).f(totalProgress).f(warmup);
    }
}
