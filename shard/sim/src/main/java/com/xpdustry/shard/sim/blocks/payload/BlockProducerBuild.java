package com.xpdustry.shard.sim.blocks.payload;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code BlockProducer.BlockProducerBuild} (BlockProducer.java:83), the base of the constructors
 * ({@link Constructor}): while it holds nothing and has a recipe and power, it builds the recipe block at
 * {@code buildSpeed} per tick; once it reaches the block's build time it takes the block's requirements (times the
 * rules' {@code buildCostMultiplier}, rounded up: its dynamic item consumer, {@code hook:block-producer.items}) and
 * holds a new building of it, which it moves out of its front. It takes up to twice each requirement.
 *
 * <p>State, by vanilla's names: {@code progress}, {@code time}, {@code heat}. Only {@code progress} is saved, after the
 * payload block's body. The place effect draws nothing on a server.
 */
public abstract class BlockProducerBuild extends PayloadBuild<Payload.BuildPayload>{
    public float progress, time, heat;

    @Derived("buildSpeed, read from the block on first use")
    private float buildSpeed = Float.NaN;
    @Derived("the consumer's stacks for consumedRecipe and consumedMultiplier, rebuilt when either changes")
    private Stacks consumed;
    @Derived("the recipe consumed was built for")
    private int consumedRecipe = -2;
    @Derived("the multiplier consumed was built for")
    private float consumedMultiplier;

    /** {@code recipe()}: the block it builds, or -1. */
    public abstract int recipe();

    private float buildSpeed(){
        if(Float.isNaN(buildSpeed)) buildSpeed = block().baked() ? block().fields().f("buildSpeed") : 0.4f;
        return buildSpeed;
    }

    /** {@code recipe().buildTime}. */
    private float recipeTime(int recipe){
        return PayloadCosts.place(world(), recipe).buildTime;
    }

    /** {@code fraction()}: the share of the recipe built. */
    public float fraction(){
        int recipe = recipe();
        return recipe == -1 ? 0f : progress / recipeTime(recipe);
    }

    @Override
    public boolean acceptItem(Building source, int item){
        return items.get(item) < getMaximumAccepted(item);
    }

    /** {@code getMaximumAccepted(item)}: twice the recipe's requirement of it. */
    @Override
    public int getMaximumAccepted(int item){
        int recipe = recipe();
        if(recipe == -1) return 0;
        var info = PayloadCosts.place(world(), recipe);
        for(int i = 0; i < info.items.length; i++){
            if(info.items[i] == item) return info.amounts[i] * 2;
        }
        return 0;
    }

    /** {@code ConsumeItemDynamic}'s lambda: the recipe's requirements, scaled by the rules and rounded up. */
    @Override
    public Stacks consumedItems(){
        int recipe = recipe();
        if(recipe == -1) return Stacks.EMPTY;
        float multiplier = rules().buildCostMultiplier;
        if(consumed == null || consumedRecipe != recipe || consumedMultiplier != multiplier){
            var info = PayloadCosts.place(world(), recipe);
            int[] pairs = new int[info.items.length * 2];
            for(int i = 0; i < info.items.length; i++){
                pairs[i * 2] = info.items[i];
                pairs[i * 2 + 1] = Mathf.ceil(info.amounts[i] * multiplier);
            }
            consumed = Stacks.items(pairs);
            consumedRecipe = recipe;
            consumedMultiplier = multiplier;
        }
        return consumed;
    }

    @Override
    public boolean acceptPayload(Building source, Payload payload){
        return false;
    }

    @Override
    public boolean shouldConsume(){
        return super.shouldConsume() && recipe() != -1 && payload == null;
    }

    @Override
    public void updateTile(){
        super.updateTile();
        int recipe = recipe();
        boolean produce = recipe != -1 && efficiency > 0 && payload == null;
        if(produce){
            progress += buildSpeed() * edelta();
            if(progress >= recipeTime(recipe)){
                consume();
                payload = new Payload.BuildPayload(world().buildings().createPayload(world().content().block(recipe), team));
                payVector.setZero();
                progress %= 1f;
            }
        }
        heat = Mathf.lerpDelta(heat, produce ? 1f : 0f, 0.15f);
        time += heat * delta();
        moveOutPayload();
    }

    @Override
    public double sense(LAccess sensor){
        if(sensor == LAccess.progress) return progress;
        return super.sense(sensor);
    }

    @Override
    protected void copyState(com.xpdustry.shard.sim.entity.Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (BlockProducerBuild)into;
        out.progress = progress;
        out.time = time;
        out.heat = heat;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.f(progress).f(time).f(heat);
    }

    @Override
    public void write(CheckpointOut out){
        super.write(out);
        out.f("progress", progress).f("time", time).f("heat", heat);
    }

    @Override
    public void read(CheckpointIn in){
        super.read(in);
        progress = in.f("progress");
        time = in.f("time");
        heat = in.f("heat");
    }
}
