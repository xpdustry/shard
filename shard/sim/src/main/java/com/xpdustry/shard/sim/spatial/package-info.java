/**
 * Where units are, for queries and pushing (note 29): {@link com.xpdustry.shard.sim.spatial.SpatialIndex}, the
 * counting-sort unit grids standing in for vanilla's {@code Groups.unit} tree and team trees, and each team's own
 * quadtree as {@code insert} builds it ({@link com.xpdustry.shard.sim.spatial.UnitTree}) for {@code nearbyEnemies};
 * {@link com.xpdustry.shard.sim.spatial.UnitPhysics}, vanilla's {@code PhysicsProcess} with its quadtree per physics layer
 * ({@link com.xpdustry.shard.sim.spatial.BodyTree});
 * {@link com.xpdustry.shard.sim.spatial.Collisions}, {@code EntityCollisions.move} against tiles. Bullets
 * are in {@code Bullets}, with vanilla's tree. {@link com.xpdustry.shard.sim.spatial.TileSolidity} answers {@code EntityCollisions}'
 * predicates from the tile grid's bitsets, which {@code Buildings} keeps current on every edit
 * ({@code TileCollisionOracleTest}); building targets come through
 * {@link com.xpdustry.shard.sim.spatial.BuildingTargets}.
 *
 * <p>Phase order. Vanilla's server frame runs posted tasks, {@code asyncCore.begin()}, then {@code logic.update()}:
 * {@code updateTeamStats} first, {@code Groups.updatePooling}, {@code bullet.updatePhysics}, {@code unit.updatePhysics},
 * then the entity updates. Shard runs:
 * <ol>
 *   <li>{@code TEAMS} -300 {@code PostedDestroys}: units a cap or environment death doomed last tick are destroyed
 *   (the {@code Call.unitDestroy} vanilla's {@code unitCapDeath} and {@code unitEnvDeath} post);</li>
 *   <li>{@code TEAMS} -200 {@code PhysicsBegin}: {@code PhysicsProcess.begin}. Bodies added since the last begin join,
 *   the step computed last time moves its units, stale bodies leave, bodies change layer, then this tick's step is
 *   computed from a snapshot. A push lands one tick after it is computed;</li>
 *   <li>{@code TEAMS} -100 {@code TeamStats}: {@code updateTeamStats}. Recounts units per team and type, rebuilds the
 *   per-team lists, fills the team grids;</li>
 *   <li>{@code PHYSICS} -300 {@code Pooling}: {@code Groups.updatePooling}, so freed bullets reach the pool and draw
 *   their reset ids;</li>
 *   <li>{@code PHYSICS} -200 {@code EntityPhysics}: {@code bullet.updatePhysics} (the bullet tree's fill) and
 *   {@code unit.updatePhysics} (all-units grid members, last positions);</li>
 *   <li>{@code SYNC} last, {@code IndexExpiry}: every grid's placement expires.</li>
 * </ol>
 * So a unit spawned mid-tick is in {@code world.units()} and counted at once, in the all-units grid from the next
 * {@code PHYSICS}, in its team's grid and list from the next {@code TEAMS}, and pushed from the begin after that.
 * Queries before a fill see the last fill's members, as vanilla's trees do. Members outside the tree bounds go to the
 * edge cells and are still found, as {@code QuadTree.fill} keeps them at the root; the team grids drop them, as the
 * team trees' {@code insert} does. A team grid keeps each member's hitbox at the fill, where vanilla's tree placed it,
 * and builds the team's {@code UnitTree} from them on the first {@code nearbyEnemies} after the fill.
 *
 * <p>Persistence. Grid members, what each fill saw, and their hitboxes at the fill are
 * {@link com.xpdustry.shard.sim.world.Persistence#AUTHORITATIVE}: stale by design between fills, like vanilla's trees.
 * A team's {@code UnitTree} is derived from them, built again node for node. Their placement in cells is derived: rebuilt on the first query
 * from current positions and dropped at the end of every tick, so a restored or forked world places them the same
 * way. Physics bodies, their layer order and each layer's random are authoritative; a layer's tree is refilled at
 * every step.
 *
 * <p>Vanilla's own trees, where the order or the reach is part of the result:
 * <ul>
 *   <li>{@code nearbyEnemies}, which bullet collisions, splash damage, lightning, lasers and flak read: each team's
 *   {@code UnitTree}, as {@code insert} built it ({@code BulletOrderOracleTest});</li>
 *   <li>pushes, a Gauss-Seidel pass that depends on the order throughout: {@link com.xpdustry.shard.sim.spatial.BodyTree},
 *   so crowds move as in vanilla;</li>
 *   <li>bullets that force fields, shockwave towers and point defense look for: {@code Bullets.intersectQuad}, the
 *   bullet group's tree, which keeps a bullet removed since the fill until the next one, as vanilla's does.</li>
 * </ul>
 *
 * <p>Deviations from vanilla, all in order or reach rather than in what is found:
 * <ul>
 *   <li>the other unit queries (targeting, counts) hand out candidates in cell order, not quadtree
 *   order ({@code nearby(team…)} and {@code nearbyEnemies} walk the trees). They return the same sets and best-of results; only exact ties depend on it;</li>
 *   <li>a layer's random for exactly stacked bodies is seeded per layer; vanilla's is unseeded;</li>
 *   <li>grid queries reach members up to 8 world units from where they were placed, vanilla's
 *   trees anywhere in the node they were filled into, so a member that moved since its fill can be found by one and
 *   missed by the other. The trees above have vanilla's reach;</li>
 *   <li>the posted destroy of a doomed unit runs after {@code COMMANDS}, where vanilla runs it with the other posted
 *   tasks, in posting order.</li>
 * </ul>
 *
 * <p>Owner: WP12, design in note 29.
 */
package com.xpdustry.shard.sim.spatial;
