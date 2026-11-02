package com.xpdustry.shard.sim.world;

import com.xpdustry.shard.common.registry.*;

/**
 * A built-in subsystem's work in one {@link Phase}. Register an implementation with {@code @Register}; the world runs
 * a phase's handlers in ({@link #order()}, class name) order, so the result never depends on class path order.
 *
 * <p>Handlers hold no state: one instance serves every world. State lives in the world, in its tables, objects and
 * {@link WorldPart}s. In the first run plugins don't register handlers (ARCHITECTURE.md §4).
 */
@Registry
public interface PhaseHandler{
    Phase phase();

    /** Lower runs first within the phase; ties break by class name. */
    default int order(){
        return 0;
    }

    /**
     * Whether this handler also runs in a {@link World#paused()} world's ticks, outside {@link Phase#COMMANDS},
     * {@link Phase#GAME_STATE} and {@link Phase#SYNC} (which run whole): only work vanilla does before
     * {@code Logic.update}'s {@code isPaused} check, such as the posted tasks.
     */
    default boolean runsWhilePaused(){
        return false;
    }

    void run(World world);
}
