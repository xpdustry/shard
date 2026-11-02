/**
 * Block family "transport": behaviour and state, one {@link com.xpdustry.shard.sim.building.Building} subclass per kind,
 * registered with {@code @Kind("<kind-key>")}. Conveyors: basic, titanium, armored ({@link
 * com.xpdustry.shard.sim.blocks.transport.ConveyorBuild}), plastanium and surge ({@link
 * com.xpdustry.shard.sim.blocks.transport.StackConveyor}); Erekir's ducts, duct routers, overflow ducts and duct
 * bridges. {@link com.xpdustry.shard.sim.blocks.transport.Blending} is vanilla's {@code Autotiler} as far as the
 * simulation reads it.
 *
 * <p>The duct unloader is {@code sim.blocks.distribution.DirectionalUnloader}.
 *
 * <p>Owner: WP31. The same owner holds {@code compat.v160.blocks.transport}, the family's v160.7 codecs.
 */
package com.xpdustry.shard.sim.blocks.transport;
