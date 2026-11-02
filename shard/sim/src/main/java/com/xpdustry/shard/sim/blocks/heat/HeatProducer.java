package com.xpdustry.shard.sim.blocks.heat;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.blocks.crafting.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code HeatProducer.HeatProducerBuild} (HeatProducer.java:43): the heaters (oxidation chamber, electric,
 * slag and phase heaters, the heat reactor, the sandbox heat source). A crafter ({@link GenericCrafterBuild}) whose
 * {@link #heat} approaches {@code heatOutput × efficiency} by {@code warmupRate} a tick, whatever the efficiency, and
 * goes out of its front to the {@link HeatConsumer} it faces.
 */
@Kind("heat-producer")
public final class HeatProducer extends GenericCrafterBuild implements HeatBlock{
    public float heat;
    @Derived("the block's heat parameters, read on first use")
    private Params params;

    /** {@code HeatProducer}'s fields. */
    record Params(float heatOutput, float warmupRate){
        static Params of(Building building){
            if(!building.block().baked()) return new Params(10f, 0.15f);
            var fields = building.block().fields();
            return new Params(fields.f("heatOutput"), fields.f("warmupRate"));
        }
    }

    private Params own(){
        if(params == null) params = Params.of(this);
        return params;
    }

    @Override
    public void updateTile(){
        super.updateTile();
        // heat approaches target at the same speed regardless of efficiency
        heat = Mathf.approachDelta(heat, own().heatOutput() * efficiency, own().warmupRate() * delta());
    }

    @Override
    public float heatFrac(){
        return heat / own().heatOutput();
    }

    @Override
    public float heat(){
        return heat;
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
        var out = (HeatProducer)into;
        out.heat = heat;
        out.params = params;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.f(heat);
    }
}
