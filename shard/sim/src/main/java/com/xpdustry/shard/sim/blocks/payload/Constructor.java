package com.xpdustry.shard.sim.blocks.payload;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.build.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code Constructor.ConstructorBuild} (Constructor.java:63), the constructor and the large constructor: a
 * {@link BlockProducerBuild} whose recipe is configured. A block config resets the progress when it changes the recipe,
 * and takes only blocks it can make ({@code canProduce}: visible, within its size range, not a core, not banned,
 * buildable here, and in its {@code filter} if it has one); a clear config forgets the recipe and keeps the progress.
 *
 * <p>State: {@code recipe} (a block id, -1 for none), saved after the producer's body as a short.
 */
@Kind("constructor")
public final class Constructor extends BlockProducerBuild{
    static final Configs<Constructor> CONFIGS = Configs.of(Constructor.class)
        .clear(tile -> tile.recipe = -1)
        .on(ContentType.BLOCK, (tile, block) -> {
            if(tile.recipe != block) tile.progress = 0f;
            if(tile.canProduce(block)) tile.recipe = block;
        });

    /** The block it builds, or -1. */
    public int recipe = -1;

    @Derived("the filter's block ids, read from the block on first use")
    private Set<Integer> filter;

    @Override
    public int recipe(){
        return recipe;
    }

    /** {@code canProduce(block)}. */
    public boolean canProduce(int block){
        BlockDef def = world().content().block(block);
        BlockDef self = block();
        int min = self.baked() ? self.fields().i("minBlockSize") : 1, max = self.baked() ? self.fields().i("maxBlockSize") : 2;
        PlaceInfo info = PayloadCosts.place(world(), block);
        return Build.isVisible(world(), info) && def.size() >= min && def.size() <= max && !info.core
            && !rules().isBlockBanned(block) && Build.environmentBuildable(world(), info) && (filter().isEmpty() || filter().contains(block));
    }

    private Set<Integer> filter(){
        if(filter == null){
            var ids = new TreeSet<Integer>();
            if(block().baked()) for(Object name : block().fields().list("filter")) ids.add(world().content().block((String)name).id());
            filter = ids;
        }
        return filter;
    }

    @Override
    protected Configs<?> configs(){
        return CONFIGS;
    }

    @Override
    public Object config(){
        return recipe == -1 ? null : ContentRef.block(recipe);
    }

    @Override
    protected void copyState(com.xpdustry.shard.sim.entity.Entity into, CopyContext context){
        super.copyState(into, context);
        ((Constructor)into).recipe = recipe;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.i(recipe);
    }

    @Override
    public void write(CheckpointOut out){
        super.write(out);
        out.i("recipe", recipe);
    }

    @Override
    public void read(CheckpointIn in){
        super.read(in);
        recipe = in.i("recipe");
    }
}
