/**
 * Block family "distribution": behaviour and state, one {@link com.xpdustry.shard.sim.building.Building} subclass per kind,
 * registered with {@code @Kind("<kind-key>")}. Junctions, routers, sorters, overflow gates, unloaders, bridges, mass drivers.
 *
 * <p>Owner: WP32 (routing), WP33 (bridges, drivers). The same owner holds {@code compat.v160.blocks.distribution}, the family's v160.7 codecs.
 */
package com.xpdustry.shard.sim.blocks.distribution;
