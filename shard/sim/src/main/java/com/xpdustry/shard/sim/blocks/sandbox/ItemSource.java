package com.xpdustry.shard.sim.blocks.sandbox;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code ItemSource.ItemSourceBuild}: dumps {@code itemsPerSecond} of its configured item, without holding
 * any. Configured with an item, cleared with null; {@link #config()} gives the item back.
 */
@Kind("item-source")
public final class ItemSource extends Building implements Checkpointable{
    static final Configs<ItemSource> CONFIGS = Configs.of(ItemSource.class)
        .on(ContentType.ITEM, (source, item) -> source.outputItem = item)
        .clear(source -> source.outputItem = -1);

    public float counter;
    /** The item it makes, or -1. */
    public int outputItem = -1;

    @Override
    public void updateTile(){
        if(outputItem == -1) return;
        counter += edelta();
        float limit = 60f / itemsPerSecond();
        while(counter >= limit){
            items.set(outputItem, 1);
            dump(outputItem);
            produced(outputItem, 1);
            items.set(outputItem, 0);
            counter -= limit;
        }
    }

    private int itemsPerSecond(){
        return block().baked() ? block().fields().i("itemsPerSecond") : 100;
    }

    @Override
    public boolean acceptItem(Building source, int item){
        return false;
    }

    @Override
    protected Configs<?> configs(){
        return CONFIGS;
    }

    @Override
    public Object config(){
        return outputItem == -1 ? null : ContentRef.item(outputItem);
    }

    @Override
    public void write(CheckpointOut out){
        out.f("counter", counter).i("outputItem", outputItem);
    }

    @Override
    public void read(CheckpointIn in){
        counter = in.f("counter");
        outputItem = in.i("outputItem");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (ItemSource)into;
        out.counter = counter;
        out.outputItem = outputItem;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.f(counter).i(outputItem);
    }
}
