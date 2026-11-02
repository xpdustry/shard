package com.xpdustry.shard.sim.toy;

import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.world.*;

/**
 * A 2×2 building that points at another building and at a unit, possibly long after they are gone, and wakes its
 * target now and then. It exercises reference remapping, cycles and stale references in forks.
 */
@Kind("test-link")
public final class Link extends Building{
    public Building target;
    public com.xpdustry.shard.sim.unit.Unit watched;
    public int pulses;
    public float[] history = new float[4];

    @Override
    public void updateTile(){
        if(target != null && target.isValid() && world().rand().chance(0.2)){
            target.noSleep();
            pulses++;
        }
        history[pulses % history.length] += world().rand().nextFloat();
        if(liquids != null) liquids.liquids[0] += 0.5f;
        if(power != null) power.status = pulses % 10 / 10f;
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (Link)into;
        out.target = context.map(target);
        out.watched = context.map(watched);
        out.pulses = pulses;
        out.history = history.clone();
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.i(target == null ? -1 : target.id()).i(watched == null ? -1 : watched.id()).i(pulses).floats(history);
    }
}
