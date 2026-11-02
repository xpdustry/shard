package com.xpdustry.shard.sim.blocks.units;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code UnitCargoUnloadPoint.UnitCargoUnloadPointBuild} (UnitCargoUnloadPoint.java): where a cargo unit
 * ({@code CargoAI}) puts its stack. Configured with the one item it asks for ({@link #item}, -1 for none; a clear
 * removes it). Units fill it whole stacks at a time ({@link #acceptStack} ignores the team and the item); it hands its
 * items to its neighbours as any block dumps, one try a tick. Full for {@code staleTimeDuration} ticks without a
 * dump, it turns {@link #stale}, and the cargo AI prefers other points; any room or any dump clears it.
 *
 * <p>Nothing else feeds it: conveyors and ducts only see the default {@code acceptItem}, which takes no item. Saved:
 * the item and {@code stale}; the timer starts over on a load.
 */
@Kind("unit-cargo-unload-point")
public final class UnitCargoUnloadPoint extends Building implements Checkpointable{
    static final Configs<UnitCargoUnloadPoint> CONFIGS = Configs.of(UnitCargoUnloadPoint.class)
        .on(ContentType.ITEM, (point, item) -> point.item = item)
        .clear(point -> point.item = -1);

    /** The item it asks for, or -1. */
    public int item = -1;
    public float staleTimer;
    public boolean stale;

    @Derived("the block's staleTimeDuration, read on first use")
    private float staleTimeDuration = -1f;

    private float staleTimeDuration(){
        if(staleTimeDuration < 0f) staleTimeDuration = block().baked() ? block().fields().f("staleTimeDuration") : 60f * 6f;
        return staleTimeDuration;
    }

    @Override
    public void updateTile(){
        int capacity = info().itemCapacity;
        if(items.total() < capacity){
            staleTimer = 0f;
            stale = false;
        }

        if(dumpAccumulate(-1)){
            staleTimer = 0f;
            stale = false;
        }else if(items.total() >= capacity && (staleTimer += 1f) >= staleTimeDuration()){
            stale = true;
        }
    }

    /** {@code acceptStack(item, amount, source)}: whatever fits, from anyone. */
    @Override
    public int acceptStack(int item, int amount, int sourceTeam){
        return Math.min(info().itemCapacity - items.total(), amount);
    }

    @Override
    protected Configs<?> configs(){
        return CONFIGS;
    }

    @Override
    public Object config(){
        return item == -1 ? null : ContentRef.item(item);
    }

    @Override
    public void write(CheckpointOut out){
        out.i("item", item).f("staleTimer", staleTimer).bool("stale", stale);
    }

    @Override
    public void read(CheckpointIn in){
        item = in.i("item");
        staleTimer = in.f("staleTimer");
        stale = in.bool("stale");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (UnitCargoUnloadPoint)into;
        out.item = item;
        out.staleTimer = staleTimer;
        out.stale = stale;
        out.staleTimeDuration = staleTimeDuration;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.i(item).f(staleTimer).bool(stale);
    }
}
