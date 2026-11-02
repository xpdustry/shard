/**
 * Block family "storage": cores ({@link com.xpdustry.shard.sim.blocks.storage.Core}, every {@code CoreBlock}) and
 * storage blocks ({@link com.xpdustry.shard.sim.blocks.storage.Storage}: containers and vaults), which link to an
 * adjacent core and share its items ({@link com.xpdustry.shard.sim.blocks.storage.SharedItems}); the core's Calls
 * ({@link com.xpdustry.shard.sim.blocks.storage.CoreEvent}), the player spawn among them. Unloaders are distribution's.
 *
 * <p>Owner: WP23. The same owner holds {@code compat.v160.blocks.storage}, the family's v160.7 codecs.
 */
package com.xpdustry.shard.sim.blocks.storage;
