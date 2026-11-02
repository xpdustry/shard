/**
 * Pathfinding (note 22): one {@link com.xpdustry.shard.sim.path.PathService} per world, flowfields with Dial's
 * bucket-queue Dijkstra and incremental repair, RTS fields under a pop budget, and vanilla's control pathfinder. No
 * path threads.
 *
 * <ul>
 *   <li>{@link com.xpdustry.shard.sim.path.PathTile}: vanilla's packed tile layout;
 *   {@link com.xpdustry.shard.sim.path.PathBlocks}: what pathing reads from content;
 *   {@link com.xpdustry.shard.sim.path.PathCost}: the cost classes and vanilla's two cost families;
 *   {@link com.xpdustry.shard.sim.path.PathRules}: the rules pathing reads, with Shard's layers off by default.</li>
 *   <li>{@link com.xpdustry.shard.sim.path.AvoidanceProcess}: vanilla's avoidance grid, which stuck wave units step
 *   around ({@link com.xpdustry.shard.sim.path.PathService#next(int, int, AvoidanceProcess, int)}; WP54).</li>
 *   <li>{@link com.xpdustry.shard.sim.path.ControlPathfinder}: vanilla's hierarchical RTS pathfinder, which
 *   {@code CommandAI}'s move and {@code LogicAI}'s {@code pathfind} ask ({@code pathPosition}), on vanilla's nominal
 *   schedule in {@code PATH_FLUSH} ({@link com.xpdustry.shard.sim.path.ControlFlush}; WP71).</li>
 *   <li>Internals: {@code PathGrid} (the view of the world at the last flush, packed tiles),
 *   {@code FieldEngine} (builds and repairs), {@code Flowfield}, {@code Threat}.</li>
 * </ul>
 *
 * <p>Owners: WP53 (flowfields), WP71 (control pathfinder), WP54 (avoidance).
 */
package com.xpdustry.shard.sim.path;
