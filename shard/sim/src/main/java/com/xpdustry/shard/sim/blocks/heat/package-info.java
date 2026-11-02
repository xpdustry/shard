/**
 * Block family "heat": Erekir's heat, one {@link com.xpdustry.shard.sim.building.Building} subclass per kind, registered
 * with {@code @Kind("<kind-key>")}. Heaters ({@link com.xpdustry.shard.sim.blocks.heat.HeatProducer}), the heat router
 * and redirectors ({@link com.xpdustry.shard.sim.blocks.heat.HeatConductor}), and the two roles vanilla's
 * {@code calculateHeat} reads:
 * <ul>
 * <li>{@link com.xpdustry.shard.sim.blocks.heat.HeatBlock}: a building that gives heat (heaters, conductors, the heater
 * generator, the thorium reactor);</li>
 * <li>{@link com.xpdustry.shard.sim.blocks.heat.HeatConsumer}: one that takes it, summing its neighbours' through
 * {@link com.xpdustry.shard.sim.blocks.heat.Heat#calculate} once a tick (heat crafters, conductors, the flux
 * reactor; Erekir's heat turrets once the turret row calls it).</li>
 * </ul>
 * Heat is not stored anywhere but in those buildings: a consumer recomputes it from its proximity every update, so a
 * broken heater or conductor stops feeding it on the next one.
 *
 * <p>Owner: WP34. The same owner holds {@code compat.v160.blocks.heat}, the family's v160.7 codecs.
 */
package com.xpdustry.shard.sim.blocks.heat;
