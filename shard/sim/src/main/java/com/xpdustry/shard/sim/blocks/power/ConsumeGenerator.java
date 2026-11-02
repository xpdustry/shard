package com.xpdustry.shard.sim.blocks.power;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.building.consume.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code ConsumeGenerator.ConsumeGeneratorBuild}: generators that burn what their consumers take, the
 * combustion, steam, differential and RTG generators and Erekir's chambers. Its efficiency is its consumers'; its
 * item or liquid filter's multiplier for what it holds scales the output ({@link #updateEfficiencyMultiplier}). With
 * items it consumes one batch whenever the last burned out ({@code generateTime}, which item duration multipliers
 * stretch); consumers count as satisfied while one burns ({@link #consumeTriggerValid}). A liquid it outputs fills it
 * and is dumped, and with {@code explodeOnFull} kills it when full.
 *
 * <p>The generate and consume effects draw their chance and positions from the world's random, as vanilla's do.
 */
@Kind("consume-generator")
public sealed class ConsumeGenerator extends Generator permits HeaterGenerator{
    public float warmup, totalTime, efficiencyMultiplier = 1f, itemDurationMultiplier = 1;
    @Derived("the block's parameters, read on first use")
    private Params params;

    /** {@code ConsumeGenerator}'s fields: durations, effects, the output liquid and the filters it found. */
    record Params(float itemDuration, float warmupSpeed, float effectChance, float generateEffectRange, float[] durationMultipliers,
        int outputLiquid, float outputAmount, boolean explodeOnFull, ConsumeItemFilter filterItem, ConsumeLiquidFilter filterLiquid){
        static Params of(Building building){
            BlockDef block = building.block();
            ContentEpoch content = building.world().content();
            Consumers consumers = building.info().consumers;
            var multipliers = new float[content.items()];
            Arrays.fill(multipliers, 1f);
            if(!block.baked()){
                return new Params(120f, 0.05f, 0.01f, 3f, multipliers, -1, 0f, false, consumers.find(ConsumeItemFilter.class),
                    consumers.find(ConsumeLiquidFilter.class));
            }
            var fields = block.fields();
            Map<String, Object> durations = fields.map("itemDurationMultipliers");
            boolean anyDuration = durations != null && !durations.isEmpty();
            if(anyDuration) durations.forEach((item, value) -> multipliers[content.id(ContentType.ITEM, item)] = ((Number)value).floatValue());
            Map<String, Object> output = fields.map("outputLiquid");
            int outputLiquid = -1;
            float outputAmount = 0f;
            if(output != null && !output.isEmpty()){
                var entry = output.entrySet().iterator().next();
                outputLiquid = content.id(ContentType.LIQUID, entry.getKey());
                outputAmount = ((Number)entry.getValue()).floatValue();
            }
            return new Params(fields.f("itemDuration"), fields.f("warmupSpeed"), fields.f("effectChance"), fields.f("generateEffectRange"),
                anyDuration ? multipliers : null, outputLiquid, outputAmount, fields.b("explodeOnFull"),
                consumers.find(ConsumeItemFilter.class), consumers.find(ConsumeLiquidFilter.class));
        }
    }

    private Params own(){
        if(params == null) params = Params.of(this);
        return params;
    }

    @Override
    public void updateEfficiencyMultiplier(){
        Params params = own();
        if(params.filterItem() != null){
            float m = params.filterItem().efficiencyMultiplier(this);
            if(m > 0) efficiencyMultiplier = m;
        }else if(params.filterLiquid() != null){
            float m = params.filterLiquid().efficiencyMultiplier(this);
            if(m > 0) efficiencyMultiplier = m;
        }
    }

    @Override
    public void updateTile(){
        Params params = own();
        Rand rand = world().rand();
        boolean valid = efficiency > 0;

        warmup = Mathf.lerpDelta(warmup, valid ? 1f : 0f, params.warmupSpeed());
        productionEfficiency = efficiency * efficiencyMultiplier;
        totalTime += warmup;

        // the generate effect, now and then: its chance and position draw from the random
        if(valid && Mathf.chanceDelta(rand, params.effectChance())){
            Mathf.range(rand, params.generateEffectRange());
            Mathf.range(rand, params.generateEffectRange());
        }

        // the multiplier doesn't change while nothing is left to consume and the last batch still burns
        if(params.filterItem() != null && valid && params.durationMultipliers() != null && params.filterItem().consumed(this) != -1){
            itemDurationMultiplier = params.durationMultipliers()[params.filterItem().consumed(this)];
        }

        // take in items periodically
        if(block().hasItems() && valid && generateTime <= 0f){
            consume();
            // the consume effect's position
            Mathf.range(rand, params.generateEffectRange());
            Mathf.range(rand, params.generateEffectRange());
            generateTime = 1f;
        }

        if(params.outputLiquid() != -1){
            int liquid = params.outputLiquid();
            float capacity = info().liquidCapacity;
            float added = Math.min(productionEfficiency * delta() * params.outputAmount(), capacity - liquids.get(liquid));
            liquids.add(liquid, added);
            dumpLiquid(liquid);
            // vanilla quirk kept: the killed generator still counts its generation time down
            if(params.explodeOnFull() && liquids.get(liquid) >= capacity - 0.01f) kill();
        }

        // generation time always goes down, but only at the end so consumeTriggerValid doesn't assume fake items
        generateTime -= delta() / (params.itemDuration() * itemDurationMultiplier);
    }

    @Override
    public boolean consumeTriggerValid(){
        return generateTime > 0;
    }

    @Override
    public float warmup(){
        return warmup;
    }

    @Override
    public void write(CheckpointOut out){
        super.write(out);
        out.f("warmup", warmup).f("totalTime", totalTime).f("efficiencyMultiplier", efficiencyMultiplier)
            .f("itemDurationMultiplier", itemDurationMultiplier);
    }

    @Override
    public void read(CheckpointIn in){
        super.read(in);
        warmup = in.f("warmup");
        totalTime = in.f("totalTime");
        efficiencyMultiplier = in.f("efficiencyMultiplier");
        itemDurationMultiplier = in.f("itemDurationMultiplier");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (ConsumeGenerator)into;
        out.warmup = warmup;
        out.totalTime = totalTime;
        out.efficiencyMultiplier = efficiencyMultiplier;
        out.itemDurationMultiplier = itemDurationMultiplier;
        out.params = params;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.f(warmup).f(totalTime).f(efficiencyMultiplier).f(itemDurationMultiplier);
    }
}
