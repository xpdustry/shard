package com.xpdustry.shard.sim.blocks.transport;

import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code StackRouter.StackRouterBuild} (StackRouter.java): the surge router. It fills up from behind with
 * one item, waits {@code speed} ticks at its efficiency plus {@code baseEfficiency} once full, then unloads the whole
 * stack to {@link #target()}s in one update.
 */
@Kind("stack-router")
public final class StackRouter extends DuctRouterBuild{
    public boolean unloading;
    @Derived("the block's baseEfficiency, read on first use")
    private float baseEfficiency = Float.NaN;

    private float baseEfficiency(){
        if(Float.isNaN(baseEfficiency)) baseEfficiency = block().baked() ? block().fields().f("baseEfficiency") : 0f;
        return baseEfficiency;
    }

    @Override
    public void updateTile(){
        float eff = enabled ? (efficiency + baseEfficiency()) : 0f;
        float cap = speed();
        int capacity = info().itemCapacity;
        if(!unloading && current != -1 && items.total() >= capacity){
            // when items are full, begin offload timer
            if(progress < cap) progress += eff;
            if(progress >= cap){
                unloading = true;
                progress %= cap;
            }
        }
        // unload as many as possible when in unloading state
        if(unloading && current != -1){
            Building target = target();
            while(target != null && items.get(current) > 0){
                target.handleItem(this, current);
                items.remove(current, 1);
                target = target();
            }
            // if out of items, unloading is over
            if(items.get(current) == 0){
                current = -1;
                unloading = false;
            }
        }
        if((current == -1 || items.get(current) == 0) && items.total() > 0) current = items.first();
        if(items.empty()){
            unloading = false;
            current = -1;
        }
    }

    @Override
    public boolean acceptItem(Building source, int item){
        return !unloading && (current == -1 || item == current) && items.total() < info().itemCapacity
            && Blending.relativeTo(world(), Blending.facingEdge(source, this), tileX(), tileY()) == rotation;
    }

    @Override
    public void write(CheckpointOut out){
        super.write(out);
        out.bool("unloading", unloading);
    }

    @Override
    public void read(CheckpointIn in){
        super.read(in);
        unloading = in.bool("unloading");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (StackRouter)into;
        out.unloading = unloading;
        out.baseEfficiency = baseEfficiency;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.bool(unloading);
    }
}
