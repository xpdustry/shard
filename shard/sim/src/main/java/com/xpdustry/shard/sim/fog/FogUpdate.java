package com.xpdustry.shard.sim.fog;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * {@code if(state.rules.fog) fogControl.update()} (Logic.java:533): right after {@code Teams.updateTeamStats}, so after
 * the unit recount ({@code TeamStats}) and the cores' part ({@code TeamCoreStats}), before anything that moves.
 */
@Register
public final class FogUpdate implements PhaseHandler{
    @Override
    public Phase phase(){
        return Phase.TEAMS;
    }

    @Override
    public int order(){
        return -80;
    }

    @Override
    public void run(World world){
        if(!world.buildings().rules().fog) return;
        FogControl fog = FogControl.of(world);
        if(fog != null) fog.update(world);
    }
}
