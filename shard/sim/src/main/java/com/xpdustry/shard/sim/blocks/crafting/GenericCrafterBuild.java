package com.xpdustry.shard.sim.blocks.crafting;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code GenericCrafter.GenericCrafterBuild} (GenericCrafter.java:208), the base of the crafter kinds: it
 * works while its consumers allow ({@code efficiency}), adds {@code getProgressIncrease(craftTime)} to
 * {@link #progress} and warms up, makes its output liquids continuously, and at a full {@code progress} consumes one
 * batch and offloads its output items ({@link #craft}). Every {@code dumpTime} ticks it dumps its output items, and
 * every tick its output liquids, each in its {@code liquidOutputDirections} entry. It stops while an output item would
 * overflow, or while its output liquids are full: all of them, or any one without {@code dumpExtraLiquid}, unless it
 * has {@code ignoreLiquidFullness}; the room left in its liquids also caps how fast it progresses.
 *
 * <p>Kinds change it through vanilla's hooks: {@link #warmupTarget}, {@link #scaleOutput}, {@code efficiencyScale} and
 * {@code getProgressIncrease}. The update and craft effects are drawn only for a building some client's renderer saw
 * ({@code wasVisible}), which is never true on a server, so they draw nothing from the random.
 */
public abstract class GenericCrafterBuild extends Building implements Checkpointable{
    public float progress, totalProgress, warmup;
    /** The fractions of output items left over from earlier crafts, one per output; null until the first craft. */
    public float[] outputAccumulator;
    @Derived("the block's parameters, read on first use")
    private Params params;

    /**
     * {@code GenericCrafter}'s fields: outputs as ids and amounts (null arrays where vanilla's are null), their
     * directions, and {@code Block.dumpTime}. {@code outputLiquid} is the first output liquid, as {@code init} writes it
     * back, or -1.
     */
    public record Params(float craftTime, float warmupSpeed, int[] outputItems, int[] outputItemAmounts, int[] outputLiquids,
        float[] outputLiquidAmounts, int[] liquidOutputDirections, boolean dumpExtraLiquid, boolean ignoreLiquidFullness,
        boolean legacyReadWarmup, int dumpTime, int outputLiquid){
        static Params of(Building building){
            BlockDef block = building.block();
            ContentEpoch content = building.world().content();
            if(!block.baked()) return new Params(80f, 0.019f, null, null, null, null, new int[]{-1}, true, false, false, 5, -1);
            var fields = block.fields();
            Map<String, Object> items = fields.map("outputItems"), liquids = fields.map("outputLiquids");
            int[] itemIds = null, itemAmounts = null, liquidIds = null;
            float[] liquidAmounts = null;
            if(items != null){
                itemIds = new int[items.size()];
                itemAmounts = new int[items.size()];
                int i = 0;
                for(var entry : items.entrySet()){
                    itemIds[i] = content.id(ContentType.ITEM, entry.getKey());
                    itemAmounts[i++] = ((Number)entry.getValue()).intValue();
                }
            }
            if(liquids != null){
                liquidIds = new int[liquids.size()];
                liquidAmounts = new float[liquids.size()];
                int i = 0;
                for(var entry : liquids.entrySet()){
                    liquidIds[i] = content.id(ContentType.LIQUID, entry.getKey());
                    liquidAmounts[i++] = ((Number)entry.getValue()).floatValue();
                }
            }
            List<Object> directions = fields.list("liquidOutputDirections");
            int[] dirs = new int[directions.size()];
            for(int i = 0; i < dirs.length; i++) dirs[i] = ((Number)directions.get(i)).intValue();
            return new Params(fields.f("craftTime"), fields.f("warmupSpeed"), itemIds, itemAmounts, liquidIds, liquidAmounts, dirs,
                fields.b("dumpExtraLiquid"), fields.b("ignoreLiquidFullness"), fields.b("legacyReadWarmup"), fields.i("dumpTime"),
                liquidIds != null && liquidIds.length > 0 ? liquidIds[0] : -1);
        }
    }

    /** The block's crafter parameters. */
    public final Params crafter(){
        if(params == null) params = Params.of(this);
        return params;
    }

    @Override
    public boolean shouldConsume(){
        Params params = crafter();
        if(params.outputItems() != null){
            for(int i = 0; i < params.outputItems().length; i++){
                if(items.get(params.outputItems()[i]) + scaleOutput(params.outputItemAmounts()[i]) > info().itemCapacity) return false;
            }
        }
        if(params.outputLiquids() != null && !params.ignoreLiquidFullness()){
            boolean allFull = true;
            for(int liquid : params.outputLiquids()){
                if(liquids.get(liquid) >= info().liquidCapacity - 0.001f){
                    if(!params.dumpExtraLiquid()) return false;
                }else{
                    // if there's still space left, it's not full for all liquids
                    allFull = false;
                }
            }
            // if there is no space left for any liquid, it can't reproduce
            if(allFull) return false;
        }
        return enabled;
    }

    @Override
    public void updateTile(){
        Params params = crafter();
        if(efficiency > 0){
            progress += getProgressIncrease(params.craftTime());
            warmup = Mathf.approachDelta(warmup, warmupTarget(), params.warmupSpeed());
            // continuously output based on efficiency
            if(params.outputLiquids() != null){
                float inc = getProgressIncrease(1f);
                for(int i = 0; i < params.outputLiquids().length; i++){
                    int liquid = params.outputLiquids()[i];
                    handleLiquid(this, liquid, Math.min(params.outputLiquidAmounts()[i] * inc, info().liquidCapacity - liquids.get(liquid)));
                }
            }
            // the update effect: only where wasVisible, never on a server
        }else{
            warmup = Mathf.approachDelta(warmup, 0f, params.warmupSpeed());
        }
        totalProgress += warmup;
        if(progress >= 1f) craft();
        dumpOutputs();
    }

    @Override
    public float getProgressIncrease(float baseTime){
        Params params = crafter();
        if(params.ignoreLiquidFullness()) return super.getProgressIncrease(baseTime);
        // limit progress increase by maximum amount of liquid it can produce
        float scaling = 1f, max = 1f;
        if(params.outputLiquids() != null){
            max = 0f;
            for(int i = 0; i < params.outputLiquids().length; i++){
                float value = (info().liquidCapacity - liquids.get(params.outputLiquids()[i])) / (scaleOutput(params.outputLiquidAmounts()[i]) * edelta());
                scaling = Math.min(scaling, value);
                max = Math.max(max, value);
            }
        }
        // when dumping excess take the maximum value instead of the minimum
        return super.getProgressIncrease(baseTime) * (params.dumpExtraLiquid() ? Math.min(max, 1f) : scaling);
    }

    /** {@code warmupTarget()}: what {@link #warmup} approaches while working. */
    public float warmupTarget(){
        return 1f;
    }

    /** {@code warmup()}. */
    public float warmup(){
        return warmup;
    }

    /** {@code scaleOutput(amount)}: scales the crafter's output dynamically. */
    public float scaleOutput(float amount){
        return amount;
    }

    /** {@code craft()}: consumes a batch, offloads the outputs it adds up to, and keeps the fraction of a cycle left. */
    public void craft(){
        consume();
        Params params = crafter();
        if(params.outputItems() != null){
            int outputs = params.outputItems().length;
            if(outputAccumulator == null || outputAccumulator.length != outputs) outputAccumulator = new float[outputs];
            for(int i = 0; i < outputs; i++){
                outputAccumulator[i] += scaleOutput(params.outputItemAmounts()[i]);
                int floored = Mathf.floor(outputAccumulator[i]);
                outputAccumulator[i] -= floored;
                for(int j = 0; j < floored; j++) offload(params.outputItems()[i]);
            }
        }
        // the craft effect: only where wasVisible
        progress %= 1f;
    }

    /** {@code dumpOutputs()}. */
    public void dumpOutputs(){
        Params params = crafter();
        if(params.outputItems() != null && timer(0, params.dumpTime() / timeScale)){
            for(int i = 0; i < params.outputItems().length; i++){
                int amount = Math.max(1, Mathf.round(scaleOutput(params.outputItemAmounts()[i])));
                for(int j = 0; j < amount; j++){
                    if(!dump(params.outputItems()[i])) break;
                }
            }
        }
        if(params.outputLiquids() != null){
            for(int i = 0; i < params.outputLiquids().length; i++){
                int dir = params.liquidOutputDirections().length > i ? params.liquidOutputDirections()[i] : -1;
                dumpLiquid(params.outputLiquids()[i], 2f, dir);
            }
        }
    }

    /** {@code progress()}. */
    public float progress(){
        return Mathf.clamp(progress);
    }

    /** {@code sensor @progress}, and {@code @totalLiquids} as the first output liquid's amount. */
    @Override
    public double sense(LAccess sensor){
        if(sensor == LAccess.progress) return progress();
        // attempt to prevent wild total liquid fluctuation, at least for crafters
        if(sensor == LAccess.totalLiquids && crafter().outputLiquid() != -1) return liquids.get(crafter().outputLiquid());
        return super.sense(sensor);
    }

    @Override
    public int getMaximumAccepted(int item){
        return info().itemCapacity;
    }

    @Override
    public void write(CheckpointOut out){
        // none and an empty one behave alike: the next craft makes one of the outputs' size
        out.f("progress", progress).f("totalProgress", totalProgress).f("warmup", warmup)
            .floats("outputAccumulator", outputAccumulator == null ? new float[0] : outputAccumulator);
    }

    @Override
    public void read(CheckpointIn in){
        progress = in.f("progress");
        totalProgress = in.f("totalProgress");
        warmup = in.f("warmup");
        float[] accumulator = in.floats("outputAccumulator");
        outputAccumulator = accumulator.length == 0 ? null : accumulator;
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (GenericCrafterBuild)into;
        out.progress = progress;
        out.totalProgress = totalProgress;
        out.warmup = warmup;
        out.outputAccumulator = outputAccumulator == null ? null : outputAccumulator.clone();
        out.params = params;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.f(progress).f(totalProgress).f(warmup).floats(outputAccumulator == null ? new float[0] : outputAccumulator);
    }
}
