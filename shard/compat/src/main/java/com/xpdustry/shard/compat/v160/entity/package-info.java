/**
 * Entity sync adapters: frozen projections of Shard's entities to the generated {@code writeSync}/{@code write}
 * layouts, and back from their save bodies.
 * <ul>
 *   <li>{@link com.xpdustry.shard.compat.v160.entity.EntityProjector}, on the world thread between ticks, freezes a
 *   unit ({@link com.xpdustry.shard.compat.v160.entity.UnitProjection}, every unit layout) or a player
 *   ({@link com.xpdustry.shard.compat.v160.entity.PlayerProjection}) and names its entity class;</li>
 *   <li>{@link com.xpdustry.shard.compat.v160.entity.WorldWireContext} freezes what the codecs ask of the world at the
 *   same boundary;</li>
 *   <li>{@link com.xpdustry.shard.compat.v160.entity.UnitSink} and
 *   {@link com.xpdustry.shard.compat.v160.entity.PlayerSink} read save bodies back into Shard entities, with vanilla's
 *   {@code read()}/{@code afterRead()} semantics;</li>
 *   <li>{@link com.xpdustry.shard.compat.v160.entity.WireControllers} and
 *   {@link com.xpdustry.shard.compat.v160.entity.WirePlans} translate controllers and build plans.</li>
 * </ul>
 * {@code EntitySyncOracleTest} holds every unit type, in two states, and players to vanilla's bytes.
 *
 * <p>Owners: WP21 (unit layouts), WP25 (snapshots).
 */
package com.xpdustry.shard.compat.v160.entity;
