package com.xpdustry.shard.sim.toy;

import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.world.*;

/** Counts its updates; now and then turns idle, and sleeps once idle long enough. */
@Kind("test-counter")
public final class Counter extends Building{
    public int count;
    public boolean idle;

    @Override
    public void updateTile(){
        count++;
        if(items != null) items.items[count % items.items.length]++;
        if(world().rand().chance(0.02)) idle = !idle;
        if(idle) sleep();
        else noSleep();
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (Counter)into;
        out.count = count;
        out.idle = idle;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.i(count).bool(idle);
    }
}
