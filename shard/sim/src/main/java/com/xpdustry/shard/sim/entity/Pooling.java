package com.xpdustry.shard.sim.entity;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code Groups.updatePooling()}, the first step of {@code Logic.updateEntities}: frees the pooled entities
 * removed since the last tick, which draws one id each ({@link EntityIds#flushFrees()}). First in {@code PHYSICS}.
 */
@Register
public final class Pooling implements PhaseHandler{
    @Override
    public Phase phase(){
        return Phase.PHYSICS;
    }

    @Override
    public int order(){
        return -300;
    }

    @Override
    public void run(World world){
        world.ids().flushFrees();
    }
}
