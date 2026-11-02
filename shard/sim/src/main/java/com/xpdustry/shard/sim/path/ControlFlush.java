package com.xpdustry.shard.sim.path;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.world.*;

/**
 * {@link Phase#PATH_FLUSH}, after {@link PathFlush}: the {@link ControlPathfinder}'s share of the flush. The tick's tile
 * changes go to it once the path grid holds them, then it runs its pass when one is due, then vanilla's
 * {@code Trigger.update} clean-up, all before any unit asks it anything. It runs in paused ticks too.
 */
@Register
public final class ControlFlush implements PhaseHandler{
    @Override
    public Phase phase(){
        return Phase.PATH_FLUSH;
    }

    /** After {@link PathFlush}, which repacks the tiles this reads. */
    @Override
    public int order(){
        return 1;
    }

    /**
     * Vanilla's control pathfinder thread runs on its wall clock through a pause, and {@code Trigger.update} (its clean-up)
     * fires before {@code Logic.update}'s {@code isPaused} check; only {@code state.updateId} stands still.
     */
    @Override
    public boolean runsWhilePaused(){
        return true;
    }

    @Override
    public void run(World world){
        ControlPathfinder control = world.find(ControlPathfinder.class);
        if(control != null) control.flush(world);
    }
}
