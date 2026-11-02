package com.xpdustry.shard.sim.blocks.transport;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code DuctRouter.DuctRouterBuild} (DuctRouter.java), shared by the duct router and the surge router
 * ({@link StackRouter}): the item it holds, its progress, and {@link #target()}, the next neighbour in dump order that
 * takes the item, never backwards. Configured with an item, that item only goes forward and the others only to the
 * sides. Disabled by logic, neither updates.
 */
public abstract sealed class DuctRouterBuild extends Building implements Checkpointable permits DuctRouter, StackRouter{
    static final Configs<DuctRouterBuild> CONFIGS = Configs.of(DuctRouterBuild.class)
        .on(ContentType.ITEM, (router, item) -> router.sortItem = item)
        .clear(router -> router.sortItem = -1);

    /** The item it sorts, or -1. */
    public int sortItem = -1;
    public float progress;
    /** The item held, or -1. */
    public int current = -1;
    @Derived("the block's speed, read on first use")
    private float speed = Float.NaN;

    /** The block's {@code speed}: ticks an item takes to cross (the surge router: to fill before unloading). */
    final float speed(){
        if(Float.isNaN(speed)) speed = block().baked() ? block().fields().f("speed") : 5f;
        return speed;
    }

    /** {@code target()}: the next neighbour in dump order that may and will take the item, advancing the dump. */
    public Building target(){
        if(current == -1) return null;
        int dump = cdump;
        int size = proximitySize();
        for(int i = 0; i < size; i++){
            Building other = proximity((i + dump) % size);
            int rel = relativeTo(other);
            if(!(sortItem != -1 && (current == sortItem) != (rel == rotation)) && rel != (rotation + 2) % 4 && other.team == team
                && other.acceptItem(this, current)){
                incrementDump(size);
                return other;
            }
            incrementDump(size);
        }
        return null;
    }

    @Override
    public int removeStack(int item, int amount){
        int removed = super.removeStack(item, amount);
        if(item == current) current = -1;
        return removed;
    }

    @Override
    public void handleStack(int item, int amount, int sourceTeam){
        super.handleStack(item, amount, sourceTeam);
        current = item;
    }

    @Override
    public void handleItem(Building source, int item){
        current = item;
        progress = -1f;
        items.add(item, 1);
        noSleep();
    }

    @Override
    protected Configs<?> configs(){
        return CONFIGS;
    }

    @Override
    public Object config(){
        return sortItem == -1 ? null : ContentRef.item(sortItem);
    }

    @Override
    public void write(CheckpointOut out){
        out.i("sortItem", sortItem).f("progress", progress).i("current", current);
    }

    @Override
    public void read(CheckpointIn in){
        sortItem = in.i("sortItem");
        progress = in.f("progress");
        current = in.i("current");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (DuctRouterBuild)into;
        out.sortItem = sortItem;
        out.progress = progress;
        out.current = current;
        out.speed = speed;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.i(sortItem).f(progress).i(current);
    }
}
