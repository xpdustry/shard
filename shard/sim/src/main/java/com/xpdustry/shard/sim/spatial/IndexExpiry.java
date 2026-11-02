package com.xpdustry.shard.sim.spatial;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.world.*;

/**
 * The last thing in a tick: drops the placement of every grid (the {@link SpatialIndex} unit grids, the bullet tree's fill),
 * keeping their members. A query before the next fill places them again from the current positions, the same way in a
 * world and in its fork or restored checkpoint.
 */
@Register
public final class IndexExpiry implements PhaseHandler{
    @Override
    public Phase phase(){
        return Phase.SYNC;
    }

    @Override
    public int order(){
        return Integer.MAX_VALUE;
    }

    @Override
    public void run(World world){
        SpatialIndex index = SpatialIndex.of(world);
        if(index != null) index.expire();
        world.bullets().expireGrid();
    }
}
