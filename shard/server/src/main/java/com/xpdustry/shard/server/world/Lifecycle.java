package com.xpdustry.shard.server.world;

/**
 * Where a world is in its life (ARCHITECTURE.md §7): {@code RUNNING → QUIESCING → HIBERNATED → WAKING → RUNNING},
 * plus {@code FAILED}.
 */
public enum Lifecycle{
    /** In memory and scheduled: ticking at 60 Hz, or at 1 Hz maintenance with time frozen while empty. */
    RUNNING,
    /** Empty for long enough: the inbox is sealed and a final checkpoint is being published. A join cancels it. */
    QUIESCING,
    /** On disk only, as its current checkpoint generation. */
    HIBERNATED,
    /** Being restored from its current generation; joins wait for it. */
    WAKING,
    /** A tick threw. The world is gone from memory; its crash bundle and last generation are on disk. */
    FAILED
}
