/**
 * Block family "power": power graphs, laser and beam nodes, diodes, batteries, illuminators, generators and reactors
 * (note 30), one {@link com.xpdustry.shard.sim.building.Building} subclass per kind, registered with
 * {@code @Kind("<kind-key>")}.
 *
 * <h2>Graphs</h2>
 * Every building with {@code hasPower} is in one {@link com.xpdustry.shard.sim.blocks.power.PowerGraph}, made with it
 * (taking an entity id after the building's, as vanilla's {@code PowerModule} does). A graph is also vanilla's
 * {@code PowerGraphUpdater}: it joins {@link com.xpdustry.shard.sim.blocks.power.PowerGraphs} (vanilla's
 * {@code Groups.powerGraph}, swap-removed) when a member joins the update list, and
 * {@link com.xpdustry.shard.sim.blocks.power.PowerUpdate} solves the group in order in the power phase. The building
 * base keeps graphs current as vanilla does: proximity callbacks merge ({@code updatePowerGraph}) and split
 * ({@code powerGraphRemoved}), a team change reflows, and {@code Building.placed()} links a finished block to the
 * nodes in range. Laser links live in {@code power.links} on both ends.
 *
 * <h2>For other kinds</h2>
 * <ul>
 * <li><b>Consumers</b> (power turrets, crafters): declare the block's {@code ConsumePower}; the base computes
 * {@code shouldConsumePower} and the graph sets {@code power.status} each tick to the satisfaction, which
 * {@code efficiency} already includes. A buffered consumer is a battery: {@code power.status} is its stored fraction.
 * Read {@code power.graph.getSatisfaction()}, {@code getPowerBalance()}, {@code getLastPowerStored()} and so on for
 * sensors.</li>
 * <li><b>Producers</b>: override {@code getPowerProduction()}; generators extend
 * {@link com.xpdustry.shard.sim.blocks.power.Generator} and set {@code productionEfficiency}.</li>
 * <li><b>Construction</b> (WP30): finish a block with {@code Buildings.setBlock} then {@code building.placed()}, as
 * {@code ConstructBlock.constructFinish} does; nodes override it to autolink. A map load or an editor placement doesn't
 * call it. Not ported: {@code Battery.overwrote} and {@code PowerNode}'s plan configs, which construction applies.</li>
 * <li><b>Explosions</b> (WP41): a generator's explosion hands area damage, fire ignitions and fireballs to
 * {@link com.xpdustry.shard.sim.blocks.power.GeneratorExplosions}, after drawing what vanilla draws;
 * {@link com.xpdustry.shard.sim.blocks.power.ExplosionDamage} is the damage pass as data, gated against vanilla.</li>
 * <li><b>Payloads</b>: {@link com.xpdustry.shard.sim.blocks.power.BeamNode#pickedUp()}; graphs of carried buildings
 * are the payload work's.</li>
 * </ul>
 *
 * <p>Owner: WP37 (graphs), WP38 (generators). The same owner holds {@code compat.v160.blocks.power}, the family's
 * v160.7 codecs.
 */
package com.xpdustry.shard.sim.blocks.power;
