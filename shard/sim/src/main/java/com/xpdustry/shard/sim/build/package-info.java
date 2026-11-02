/**
 * Construction (WP30): placing and breaking blocks, construct sites, builder units and team rebuild plans.
 * <ul>
 *   <li>{@link com.xpdustry.shard.sim.build.Build}: vanilla's {@code Build} rules ({@code validPlace},
 *   {@code validBreak}, the core radius, per-block {@code canPlaceOn}) and the remote methods that start and finish
 *   construction ({@code beginPlace}, {@code beginBreak}, {@code constructFinish}, {@code deconstructFinish}), each
 *   publishing its {@link com.xpdustry.shard.sim.build.BuildEvent} after its handler, in vanilla's Call order;</li>
 *   <li>{@link com.xpdustry.shard.sim.build.ConstructBuild}: the {@code build1}–{@code build16} sites, their progress
 *   and the accumulators that take a block's cost from the core and refund it;</li>
 *   <li>{@link com.xpdustry.shard.sim.build.Builder}: {@code BuilderComp}, run each tick by the registered
 *   {@link com.xpdustry.shard.sim.build.BuilderComponent} over the unit's {@code plans}, which a player's
 *   {@code clientSnapshot} replaces and AI fills;</li>
 *   <li>{@link com.xpdustry.shard.sim.build.RebuildPlans}: destroyed blocks become their team's
 *   {@link com.xpdustry.shard.sim.team.BlockPlan}s, built-over plans go;</li>
 *   <li>{@link com.xpdustry.shard.sim.build.Construction}: the world part with construction's own state and tables.</li>
 * </ul>
 * Finishing a block calls {@code Buildings.setBlock}, then {@code configured}, {@code overwrote} (a kind keeps what the
 * buildings it replaced held), the block's {@code placeEnded} ({@link com.xpdustry.shard.sim.build.PlaceEnded}), and
 * {@code placed()}, which autolinks power nodes. The save and sync bytes of a site are {@code compat.v160.build}'s.
 *
 * <p>Owner: WP30.
 */
package com.xpdustry.shard.sim.build;
