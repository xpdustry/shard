/**
 * Block family "liquid": behaviour and state, one {@link com.xpdustry.shard.sim.building.Building} subclass per kind,
 * registered with {@code @Kind("<kind-key>")}. Conduits ({@link com.xpdustry.shard.sim.blocks.liquid.ConduitBuild}),
 * liquid routers (with containers and tanks), junctions, the bridge conduits and the reinforced bridge conduit. Liquid
 * moves through the base's helpers ({@code moveLiquid}, {@code dumpLiquid}); leaks become puddles in
 * {@code sim.puddle}. The liquid source and void are in {@code sim.blocks.sandbox}; {@code LiquidOracleTest} holds
 * them all to vanilla.
 *
 * <p>Owner: WP36. The same owner holds {@code compat.v160.blocks.liquid}, the family's v160.7 codecs.
 */
package com.xpdustry.shard.sim.blocks.liquid;
