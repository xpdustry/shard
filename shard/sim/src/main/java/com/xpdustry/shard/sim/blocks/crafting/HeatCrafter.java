package com.xpdustry.shard.sim.blocks.crafting;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.blocks.heat.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code HeatCrafter.HeatCrafterBuild} (HeatCrafter.java:43): Erekir's crafters that need heat from the
 * heaters touching them (the atmospheric concentrator, the carbide, surge and phase crucibles, the cyanogen
 * synthesizer). It sums its heat at the start of each update ({@link Heat#calculate}); without any it doesn't consume,
 * and its efficiency scales with the heat against {@code heatRequirement}, past it by {@code overheatScale}, up to
 * {@code maxEfficiency}. The consumers read that scale before the update, so they see last tick's heat.
 *
 * <p>Vanilla saves none of its heat: a loaded crafter has none until its first update.
 */
@Kind("heat-crafter")
public final class HeatCrafter extends GenericCrafterBuild implements HeatConsumer{
    public float[] sideHeat = new float[4];
    public float heat;
    @Derived("the block's heat parameters, read on first use")
    private Params params;

    /** {@code HeatCrafter}'s fields. */
    record Params(float heatRequirement, float overheatScale, float maxEfficiency){
        static Params of(Building building){
            if(!building.block().baked()) return new Params(10f, 1f, 4f);
            var fields = building.block().fields();
            return new Params(fields.f("heatRequirement"), fields.f("overheatScale"), fields.f("maxEfficiency"));
        }
    }

    private Params own(){
        if(params == null) params = Params.of(this);
        return params;
    }

    @Override
    public void updateTile(){
        heat = Heat.calculate(this, sideHeat);
        super.updateTile();
    }

    @Override
    public BlockStatus status(double stateTick){
        if(own().heatRequirement() > 0 && heat <= 0f && enabled) return BlockStatus.noInput;
        return super.status(stateTick);
    }

    @Override
    public boolean shouldConsume(){
        return (own().heatRequirement() <= 0f || heat > 0) && super.shouldConsume();
    }

    @Override
    public float heatRequirement(){
        return own().heatRequirement();
    }

    @Override
    public float[] sideHeat(){
        return sideHeat;
    }

    @Override
    public float warmupTarget(){
        return Mathf.clamp(heat / own().heatRequirement());
    }

    /** {@code sensor @heat}. */
    @Override
    public double sense(LAccess sensor){
        if(sensor == LAccess.heat) return heat;
        return super.sense(sensor);
    }

    @Override
    public float efficiencyScale(){
        Params params = own();
        float over = Math.max(heat - params.heatRequirement(), 0f);
        return Math.min(Mathf.clamp(heat / params.heatRequirement()) + over / params.heatRequirement() * params.overheatScale(), params.maxEfficiency());
    }

    @Override
    public void write(CheckpointOut out){
        super.write(out);
        out.floats("sideHeat", sideHeat).f("heat", heat);
    }

    @Override
    public void read(CheckpointIn in){
        super.read(in);
        in.floats("sideHeat", sideHeat);
        heat = in.f("heat");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (HeatCrafter)into;
        out.sideHeat = sideHeat.clone();
        out.heat = heat;
        out.params = params;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.floats(sideHeat).f(heat);
    }
}
