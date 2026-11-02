package com.xpdustry.shard.sim.world;

import java.lang.annotation.*;

/**
 * Marks a field as {@link Persistence#DERIVED} (ARCHITECTURE.md §4): checkpoints don't store it, and a restore rebuilds
 * it to exactly its value at a tick boundary, in {@code Checkpointable.restored()}, a derived {@link WorldPart}, a
 * {@code RebuildHook} or the owner's own rebuild. A field that is stale on purpose between ticks is not derived: store
 * it. Forks copy derived fields too, so a fork never has to rebuild anything. Every unmarked field of stored state is
 * authoritative, and the coverage test fails until it is stored.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Derived{
    /** What rebuilds it, e.g. {@code "setType: type.drag"}. */
    String value();
}
