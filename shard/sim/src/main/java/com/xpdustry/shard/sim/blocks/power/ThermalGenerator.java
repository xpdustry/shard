package com.xpdustry.shard.sim.blocks.power;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.environment.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code ThermalGenerator.ThermalGeneratorBuild}: the thermal generator and the turbine condenser. Its
 * efficiency is its floor's attribute ({@link #sum}, taken when its proximity is rebuilt) plus the environment's; a
 * liquid it outputs fills it at that rate and is dumped. A disabled one does nothing ({@code noUpdateDisabled}).
 *
 * <p>The environment's attribute is {@code state.envAttrs} ({@link #env}). The generate effect's chance and position
 * draw from the world's random.
 */
@Kind("thermal-generator")
public final class ThermalGenerator extends Generator{
    public float sum;
    @Derived("the block's attribute, effect chance and output liquid, read on first use")
    private Params params;

    record Params(int attribute, float effectChance, int outputLiquid, float outputAmount){
        static Params of(Building building){
            BlockDef block = building.block();
            if(!block.baked()) return new Params(Attributes.HEAT, 0.05f, -1, 0f);
            var fields = block.fields();
            var output = fields.map("outputLiquid");
            int liquid = -1;
            float amount = 0f;
            if(output != null && !output.isEmpty()){
                var entry = output.entrySet().iterator().next();
                liquid = building.world().content().id(ContentType.LIQUID, entry.getKey());
                amount = ((Number)entry.getValue()).floatValue();
            }
            return new Params(Attributes.id(fields.s("attribute")), fields.f("effectChance"), liquid, amount);
        }
    }

    private Params own(){
        if(params == null) params = Params.of(this);
        return params;
    }

    @Override
    public void updateTile(){
        Params params = own();
        productionEfficiency = sum + env(params.attribute());

        if(productionEfficiency > 0.1f && Mathf.chanceDelta(world().rand(), params.effectChance())){
            Mathf.range(world().rand(), 3f);
            Mathf.range(world().rand(), 3f);
        }

        if(params.outputLiquid() != -1 && block().hasLiquids()){
            float added = Math.min(productionEfficiency * delta() * params.outputAmount(), info().liquidCapacity - liquids.get(params.outputLiquid()));
            liquids.add(params.outputLiquid(), added);
            dumpLiquid(params.outputLiquid());
        }
    }

    @Override
    public void onProximityAdded(){
        super.onProximityAdded();
        BlockDef block = block();
        boolean floats = block.baked() && (block.floating() || block.placeableLiquid());
        sum = world().tiles().sumAttribute(own().attribute(), tileX(), tileY(), block.size(), floats);
    }

    @Override
    public void write(CheckpointOut out){
        super.write(out);
        out.f("sum", sum);
    }

    @Override
    public void read(CheckpointIn in){
        super.read(in);
        sum = in.f("sum");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (ThermalGenerator)into;
        out.sum = sum;
        out.params = params;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.f(sum);
    }

    /** {@code afterPickedUp()}: off its tiles, it sums no heat. */
    @Override
    public void afterPickedUp(){
        super.afterPickedUp();
        sum = 0f;
    }
}
