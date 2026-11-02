package com.xpdustry.shard.sim.objectives;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.world.*;

/**
 * {@code state.rules.objectives.update()} in {@code Logic.update} (Logic.java:589): after the weather and the teams'
 * AIs, before the wave countdown, unless the game is the editor's.
 */
@Register
public final class ObjectiveUpdate implements PhaseHandler{
    @Override
    public Phase phase(){
        return Phase.OBJECTIVES;
    }

    @Override
    public void run(World world){
        WorldRules part = world.find(WorldRules.class);
        if(part == null || part.rules.editor || part.rules.objectives.all.isEmpty()) return;
        part.rules.objectives.update(world, part.rules);
    }
}
