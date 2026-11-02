/**
 * What a v160.7 server sends a world's players besides the world stream (WP25, ARCHITECTURE.md §8): the snapshot
 * bodies {@code NetServer.sync} writes, and the {@code Call}s its domain events stand for.
 * <ul>
 *   <li>{@link com.xpdustry.shard.compat.v160.sync.EntitySnapshots}: {@code Groups.sync} in its order, each entity's
 *   id, class and {@code writeSync}, in packets that close once they pass 800 bytes;</li>
 *   <li>{@link com.xpdustry.shard.compat.v160.sync.BlockSnapshots}: every synced building of each present team, its
 *   position, block and {@code writeAll} without the version byte;</li>
 *   <li>{@link com.xpdustry.shard.compat.v160.sync.GameState}: the state snapshot's wave, its countdown, the enemy count, the
 *   tick rate, the logic random and the first core's items of each team;</li>
 *   <li>{@link com.xpdustry.shard.compat.v160.sync.HealthUpdates}: {@code buildHealthUpdate}'s queue and
 *   batches;</li>
 *   <li>{@link com.xpdustry.shard.compat.v160.sync.CallMapping}: the registry from a {@code WorldEvent} family to the
 *   packets vanilla sends for it, and to whom ({@link com.xpdustry.shard.compat.v160.sync.CallSink}). A family adds
 *   its mapping as a {@code @Register} class next to the others; nothing dispatches on event types by hand.</li>
 * </ul>
 * Everything here runs on the world's thread between ticks, reading the world as it is then, and produces wire
 * {@code Packet}s; {@code server.sync} turns them into frames and decides who gets them.
 *
 * <p>Owner: WP25.
 */
package com.xpdustry.shard.compat.v160.sync;
