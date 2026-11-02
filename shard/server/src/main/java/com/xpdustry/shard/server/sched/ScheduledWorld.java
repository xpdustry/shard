package com.xpdustry.shard.server.sched;

import com.xpdustry.shard.sim.world.*;

/**
 * What the {@link WorldScheduler} runs. Every method is called on a tick thread, one thread at a time per world, so
 * the world needs no locks; between turns the world may move to another tick thread.
 */
public interface ScheduledWorld{
    /** Runs exactly one tick (Δ = 1), reporting phases to {@code probe}. */
    void tick(TickProbe probe);

    /** One turn of an empty world in {@link WorldScheduler.Mode#MAINTENANCE}: no tick, simulation time stays put. */
    default void maintain(){
    }

    /**
     * Called after every turn, at a tick boundary: the place for captures and lifecycle steps. It may change the
     * slot's mode, which applies before the next turn.
     */
    default void boundary(){
    }

    /** A tick or turn threw; the slot is parked and won't run again until its mode is set. */
    void failed(Throwable error);
}
