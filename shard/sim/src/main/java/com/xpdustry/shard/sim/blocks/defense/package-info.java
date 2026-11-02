/**
 * Block family "defense": behaviour and state, one {@link com.xpdustry.shard.sim.building.Building} subclass per kind,
 * registered with {@code @Kind("<kind-key>")}. Walls, doors, shield walls, mines, menders, overdrive, force and regen projectors, radar.
 *
 * <p>Owner: WP39 (walls, doors), WP63 (projectors). The same owner holds {@code compat.v160.blocks.defense}, the family's v160.7 codecs.
 */
package com.xpdustry.shard.sim.blocks.defense;
