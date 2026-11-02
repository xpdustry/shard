package com.xpdustry.shard.sim.world;

/**
 * An immutable fact about a committed change (a block built, a unit destroyed, a wave started), published to
 * external observers after the tick that caused it (ARCHITECTURE.md §4 "Plugins and observers"). Implementations are
 * records owned by the subsystem that emits them. An observer that wants to act sends a {@link Command} for a future
 * tick.
 */
public interface WorldEvent{
}
