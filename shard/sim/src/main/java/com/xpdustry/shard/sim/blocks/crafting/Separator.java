package com.xpdustry.shard.sim.blocks.crafting;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.building.consume.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code Separator.SeparatorBuild} (Separator.java:72): the separator and the disassembler. Like a crafter it
 * progresses by {@code craftTime} while its consumers allow; at a full cycle it picks one of its {@code results},
 * weighted by their amounts, consumes, and offloads the result unless it holds a full stack of it. It dumps anything but
 * what it consumes every {@code dumpTime} ticks, and stops consuming once what it holds besides its input fills it.
 *
 * <p>The pick is {@code Mathf.randomSeed(seed++, 0, sum - 1)}: a generator of its own seeded with {@link #seed}, which
 * the world's random never sees. {@link #seed} starts from the building's position ({@link #created}) and is saved, so a
 * loaded separator picks what it would have. Vanilla's comment calls this a guaranteed desync; with the seed saved and
 * synced it isn't one.
 */
@Kind("separator")
public final class Separator extends Building implements Checkpointable{
    public float progress, totalProgress, warmup;
    public int seed;
    @Derived("the block's parameters, read on first use")
    private Params params;

    /** {@code Separator}'s fields, {@code results} as ids and weights, and its {@code consItems} (or null). */
    record Params(float craftTime, int[] results, int[] weights, int dumpTime, ConsumeItems consItems){
        static Params of(Building building){
            BlockDef block = building.block();
            ContentEpoch content = building.world().content();
            ConsumeItems consItems = building.info().consumers.find(ConsumeItems.class);
            if(!block.baked()) return new Params(0f, new int[0], new int[0], 5, consItems);
            var fields = block.fields();
            Map<String, Object> results = fields.map("results");
            int[] ids = new int[results.size()], weights = new int[results.size()];
            int i = 0;
            for(var entry : results.entrySet()){
                ids[i] = content.id(ContentType.ITEM, entry.getKey());
                weights[i++] = ((Number)entry.getValue()).intValue();
            }
            return new Params(fields.f("craftTime"), ids, weights, fields.i("dumpTime"), consItems);
        }
    }

    private Params own(){
        if(params == null) params = Params.of(this);
        return params;
    }

    @Override
    public void created(){
        seed = Mathf.randomSeed(pos(), 0, Integer.MAX_VALUE - 1);
    }

    @Override
    public boolean shouldConsume(){
        int total = items.total();
        // vanilla's "very inefficient way of allowing separators to ignore input buffer storage"
        ConsumeItems consItems = own().consItems();
        if(consItems != null){
            Stacks stacks = consItems.stacks(this);
            for(int i = 0; i < stacks.size(); i++) total -= items.get(stacks.id(i));
        }
        return total < info().itemCapacity && enabled;
    }

    @Override
    public void updateTile(){
        Params params = own();
        totalProgress += warmup * delta();
        if(efficiency > 0){
            progress += getProgressIncrease(params.craftTime());
            warmup = Mathf.lerpDelta(warmup, 1f, 0.02f);
        }else{
            warmup = Mathf.lerpDelta(warmup, 0f, 0.02f);
        }
        if(progress >= 1f){
            progress %= 1f;
            int sum = 0;
            for(int weight : params.weights()) sum += weight;
            int i = Mathf.randomSeed(seed++, 0, sum - 1);
            int count = 0, item = -1;
            for(int r = 0; r < params.results().length; r++){
                if(i >= count && i < count + params.weights()[r]){
                    item = params.results()[r];
                    break;
                }
                count += params.weights()[r];
            }
            consume();
            if(item != -1 && items.get(item) < info().itemCapacity) offload(item);
        }
        if(timer(0, params.dumpTime() / timeScale)) dump();
    }

    @Override
    public boolean canDump(Building to, int item){
        return !info().consumesItem(item);
    }

    /** {@code warmup()}. */
    public float warmup(){
        return warmup;
    }

    /** {@code sensor @progress}: unclamped, unlike a crafter's. */
    @Override
    public double sense(LAccess sensor){
        if(sensor == LAccess.progress) return progress;
        return super.sense(sensor);
    }

    @Override
    public void write(CheckpointOut out){
        out.f("progress", progress).f("totalProgress", totalProgress).f("warmup", warmup).i("seed", seed);
    }

    @Override
    public void read(CheckpointIn in){
        progress = in.f("progress");
        totalProgress = in.f("totalProgress");
        warmup = in.f("warmup");
        seed = in.i("seed");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (Separator)into;
        out.progress = progress;
        out.totalProgress = totalProgress;
        out.warmup = warmup;
        out.seed = seed;
        out.params = params;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.f(progress).f(totalProgress).f(warmup).i(seed);
    }

    /** {@code created()} again when a payload unit places it: its seed follows its new tile. */
    @Override
    public void recreated(){
        created();
    }
}
