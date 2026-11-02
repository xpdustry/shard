package com.xpdustry.shard.sim.spatial;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.world.*;

/**
 * {@code asyncCore.begin()}: {@link UnitPhysics#begin()}, after the frame's posted tasks and before
 * {@code Logic.update}. In {@code TEAMS}, after the cap deaths and before the team recount.
 */
@Register
public final class PhysicsBegin implements PhaseHandler{
    @Override
    public Phase phase(){
        return Phase.TEAMS;
    }

    @Override
    public int order(){
        return -200;
    }

    @Override
    public void run(World world){
        UnitPhysics physics = UnitPhysics.of(world);
        if(physics != null) physics.begin();
    }
}
