package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.world.*;

/**
 * The {@code Call.unitDestroy} tasks that {@code Units.unitCapDeath} and {@code unitEnvDeath} post: they run with the
 * next frame's posted tasks, before physics begins and before the team recount, so a doomed unit is gone from both
 * ({@link TeamUnits#doomed()}). First in {@code TEAMS}. The new ids {@code handleUnitPayload} posts run with them,
 * first: neither kind reads what the other changes.
 */
@Register
public final class PostedDestroys implements PhaseHandler{
    @Override
    public Phase phase(){
        return Phase.TEAMS;
    }

    @Override
    public int order(){
        return -300;
    }

    /** {@code Logic.update} does this before its {@code isPaused} check. */
    @Override
    public boolean runsWhilePaused(){
        return true;
    }

    @Override
    public void run(World world){
        TeamUnits counts = TeamUnits.of(world);
        if(counts == null) return;
        counts.renumberPosted(world);
        counts.destroyDoomed();
    }
}
