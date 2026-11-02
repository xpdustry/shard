package com.xpdustry.shard.sim.world;

/**
 * Watches one tick from outside the sim, for per-phase accounting (ARCHITECTURE.md §7: per-system, per-world CPU
 * feeds {@code /shard profile}). {@link World#tick(TickProbe)} calls {@link #enter(Phase)} before each phase that
 * has work (admission, timers or handlers) and {@link #exit()} once the last one ends. The sim never reads a clock;
 * the probe may, and what it measures never flows back into world state.
 */
public interface TickProbe{
    /** A probe that measures nothing. */
    TickProbe NONE = new TickProbe(){
        @Override
        public void enter(Phase phase){
        }

        @Override
        public void exit(){
        }
    };

    /** {@code phase} starts now; the previous phase, if any, has ended. */
    void enter(Phase phase);

    /** The tick's last phase has ended. */
    void exit();
}
