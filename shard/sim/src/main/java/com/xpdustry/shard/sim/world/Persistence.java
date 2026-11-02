package com.xpdustry.shard.sim.world;

/**
 * How a piece of state is treated by checkpoints (ARCHITECTURE.md §4 "Checkpoint contract", note 34). A fork copies
 * authoritative and derived state alike, so it continues bit for bit; a checkpoint stores only authoritative state.
 */
public enum Persistence{
    /**
     * The simulation's truth: columns, object state, active-list order, {@code Rand} state, timers, rules, plans,
     * attachments. Stored in checkpoints and covered by {@link WorldHash}.
     */
    AUTHORITATIVE,
    /**
     * Indexes rebuilt from authoritative state on restore (spatial grids, flowfields, chunk buckets). A derived
     * structure must rebuild to exactly its state at a tick boundary; one that is stale by design between ticks (an
     * index built mid-tick and read next tick) is authoritative, or the restored world diverges.
     */
    DERIVED,
    /** Not world state at all: the inbox, observer queues. Neither forked nor stored. */
    EXTERNAL
}
