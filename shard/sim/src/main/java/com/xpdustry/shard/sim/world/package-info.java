/**
 * The world root and its contracts (ARCHITECTURE.md §3, §4): {@link com.xpdustry.shard.sim.world.World}, the fixed
 * {@link com.xpdustry.shard.sim.world.Phase}s and registered {@link com.xpdustry.shard.sim.world.PhaseHandler}s,
 * {@link com.xpdustry.shard.sim.world.WorldPart}s, the bounded {@link com.xpdustry.shard.sim.world.Inbox}, intents
 * and commands, admission and the {@link com.xpdustry.shard.sim.world.CommandLog}, the journal, observers,
 * {@link com.xpdustry.shard.sim.world.Timers} (vanilla {@code Time.run}), {@link com.xpdustry.shard.sim.world.WorldHash},
 * and forking through {@link com.xpdustry.shard.sim.world.CopyContext} with
 * {@link com.xpdustry.shard.sim.world.Persistence} classes.
 *
 * <p>Owner: WP13 (tick, timers, multi-world scheduling hooks).
 */
package com.xpdustry.shard.sim.world;
