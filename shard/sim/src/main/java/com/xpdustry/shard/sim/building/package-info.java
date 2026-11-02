/**
 * Buildings (ARCHITECTURE.md §3): the {@link com.xpdustry.shard.sim.building.Building} base, the
 * {@link com.xpdustry.shard.sim.building.Kind} annotation and {@link com.xpdustry.shard.sim.building.KindRegistry} that bind
 * content kind keys to subclasses, the dense {@link com.xpdustry.shard.sim.building.Buildings} registry the tile grid
 * indexes with the placement primitives, proximity and chunk index, the
 * {@link com.xpdustry.shard.sim.building.PassthroughBuilding} for unported kinds, the item, liquid and power modules,
 * and the update handler. WP20 adds consumers, efficiency, dump/offload, damage and config dispatch.
 *
 * <p>Owner: WP20, with the Router as the reference kind that later families copy; WP11 (wave 1) for placement,
 * proximity and passthrough.
 */
package com.xpdustry.shard.sim.building;
