package com.xpdustry.shard.sim.blocks.crafting;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.environment.Attributes;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.weather.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code AttributeCrafter.AttributeCrafterBuild} (AttributeCrafter.java:105): a crafter sped up (and, with
 * {@code outputScale}, made to yield more) by its floor's attribute, the silicon crucible's heat, the cultivator's
 * spores, the vent condenser's steam. {@link #attrsum} is the attribute summed over its tiles, taken whenever its
 * proximity changes; the multipliers add the environment's attribute ({@code state.envAttrs}, rules and weather).
 *
 */
@Kind("attribute-crafter")
public final class AttributeCrafter extends GenericCrafterBuild{
    /** Vanilla saves none of it: proximity rebuilds it when a save loads. */
    public float attrsum;
    @Derived("the block's attribute parameters, read on first use")
    private Params params;

    /** {@code AttributeCrafter}'s fields. */
    record Params(int attribute, float baseEfficiency, float maxBoost, float outputScale, float boostScale, boolean scaleLiquidConsumption){
        static Params of(BlockDef block){
            if(!block.baked()) return new Params(Attributes.HEAT, 1f, 1f, 0f, 1f, false);
            var fields = block.fields();
            return new Params(Attributes.id(fields.s("attribute")), fields.f("baseEfficiency"), fields.f("maxBoost"), fields.f("outputScale"),
                fields.f("boostScale"), fields.b("scaleLiquidConsumption"));
        }
    }

    private Params own(){
        if(params == null) params = Params.of(block());
        return params;
    }

    @Override
    public float getProgressIncrease(float base){
        return super.getProgressIncrease(base) * efficiencyMultiplier();
    }

    /** {@code outputMultiplier()}. */
    public float outputMultiplier(){
        Params params = own();
        return params.baseEfficiency() + Math.min(params.maxBoost(), params.outputScale() * attrsum) + env(params.attribute());
    }

    /** {@code efficiencyMultiplier()}. */
    public float efficiencyMultiplier(){
        Params params = own();
        return params.baseEfficiency() + Math.min(params.maxBoost(), params.boostScale() * attrsum) + env(params.attribute());
    }

    @Override
    public float efficiencyScale(){
        return own().scaleLiquidConsumption() ? efficiencyMultiplier() : super.efficiencyScale();
    }

    @Override
    public float scaleOutput(float amount){
        return own().outputScale() > 0f ? amount * outputMultiplier() : amount;
    }

    @Override
    public void onProximityUpdate(){
        super.onProximityUpdate();
        BlockDef block = block();
        boolean floats = block.baked() && (block.floating() || block.placeableLiquid());
        attrsum = world().tiles().sumAttribute(own().attribute(), tileX(), tileY(), block.size(), floats);
    }

    /** {@code attribute.env()}: {@code state.envAttrs}, as of this tick's {@code ENV} phase; the rules' without weather. */
    private float env(int attribute){
        Weathers weathers = Weathers.of(world());
        return weathers != null ? weathers.envAttrs().get(attribute) : rules().attributes.get(attribute);
    }

    @Override
    public void write(CheckpointOut out){
        super.write(out);
        out.f("attrsum", attrsum);
    }

    @Override
    public void read(CheckpointIn in){
        super.read(in);
        attrsum = in.f("attrsum");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (AttributeCrafter)into;
        out.attrsum = attrsum;
        out.params = params;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.f(attrsum);
    }

    /** {@code pickedUp()}: a payload unit lifts it, and its attribute and warmup go. */
    @Override
    public void pickedUp(){
        attrsum = 0f;
        warmup = 0f;
    }
}
