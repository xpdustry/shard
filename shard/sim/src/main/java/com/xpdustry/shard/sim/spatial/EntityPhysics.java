package com.xpdustry.shard.sim.spatial;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * {@code Groups.bullet.updatePhysics()} then {@code Groups.unit.updatePhysics()}, right after
 * {@code Groups.updatePooling()} ({@link Pooling}) in {@code Logic.updateEntities}: each fills its group's tree and then
 * records every member's last position ({@code EntityCollisions.updatePhysics}).
 */
@Register
public final class EntityPhysics implements PhaseHandler{
    @Override
    public Phase phase(){
        return Phase.PHYSICS;
    }

    @Override
    public int order(){
        return -200;
    }

    @Override
    public void run(World world){
        world.bullets().updatePhysics(world.tiles().width(), world.tiles().height());
        ActiveList<Unit> units = world.units();
        SpatialIndex index = SpatialIndex.of(world);
        if(index != null) index.fillUnits(units);
        for(int i = 0; i < units.size(); i++) units.get(i).updateLastPosition();
    }
}
