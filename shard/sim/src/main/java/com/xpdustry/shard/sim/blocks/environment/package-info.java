/**
 * Block family "environment": floors, walls, ores, props, vents (vanilla {@code world/blocks/environment}). They are
 * data-driven: {@link com.xpdustry.shard.sim.blocks.environment.Environment} registers one kind key per vanilla class
 * as {@link com.xpdustry.shard.sim.building.TileKinds}, since none has a building; the tile grid keeps their predicates
 * ({@link com.xpdustry.shard.sim.tile.BlockTable}, {@link com.xpdustry.shard.sim.tile.TileGrid}), and the few rules of
 * their own live here ({@link com.xpdustry.shard.sim.blocks.environment.SteamVents}).
 *
 * <p>Owner: WP27. The same owner holds {@code compat.v160.blocks.environment}, the family's v160.7 codecs.
 */
package com.xpdustry.shard.sim.blocks.environment;
