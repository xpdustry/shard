/**
 * Units (ARCHITECTURE.md §3): the one {@link com.xpdustry.shard.sim.unit.Unit} class with its composed parts (a
 * {@link com.xpdustry.shard.sim.unit.MoveShape}, weapon mounts, abilities, statuses, plans, payload, controller), its
 * lifecycle in vanilla's {@code UnitType.create}/{@code add()}/{@code remove()} order, and
 * {@link com.xpdustry.shard.sim.unit.TeamUnits}, the per-team lists and counts by type behind unit caps.
 *
 * <p>Creation draws what vanilla draws, in its order: the entity id, the field initializers ({@code crawlTime},
 * {@code resupplyTime}), the type's controller's timers ({@link com.xpdustry.shard.sim.unit.Controllers}), then a
 * legged unit's step phase when it is added. The type fields and entity-class traits the unit core reads are read once
 * per world into {@link com.xpdustry.shard.sim.unit.UnitParams} ({@link com.xpdustry.shard.sim.unit.UnitTable}). The
 * phase order of counting, cap deaths and physics is in the {@code sim.spatial} package comment.
 *
 * <p>The update. {@link com.xpdustry.shard.sim.unit.Unit#update()} runs the unit class's merged {@code update()}: the
 * registered {@link com.xpdustry.shard.sim.unit.UnitComponent}s it has, in the annotation processor's order (priority,
 * then component name). {@code UnitComp}'s body ({@link com.xpdustry.shard.sim.unit.UnitCoreComponent}) keeps units
 * in bounds, drowns them, kills them where the environment or solid ground forbids them, and runs the controller.
 * Groups: units are in {@code Groups.unit} ({@link com.xpdustry.shard.sim.world.World#units()}) and {@code Groups.sync}
 * ({@link com.xpdustry.shard.sim.entity.EntityGroups}), never in {@code Groups.all}; nothing on a server draws, so
 * {@code Groups.draw} is not kept. v160.7 has no segmented unit class ({@code Segmentc}), so there is no segment code.
 *
 * <p>Lifecycle. Deaths are vanilla's remote calls: {@link com.xpdustry.shard.sim.unit.UnitCalls} runs the local
 * handler, then publishes the {@link com.xpdustry.shard.sim.unit.UnitEvent} the sync layer turns into the call, in the
 * order vanilla sends them ({@code UnitCallOrderOracleTest}). Caps come from the rules
 * ({@link com.xpdustry.shard.sim.unit.Units#getCap}); a cap or environment death destroys the unit with the next
 * tick's posted tasks ({@link com.xpdustry.shard.sim.unit.PostedDestroys}). Controllers come from the type
 * ({@link com.xpdustry.shard.sim.unit.Controllers}): a {@link com.xpdustry.shard.sim.ai.CommandAi} with its type's
 * default command, an AI, or {@link com.xpdustry.shard.sim.unit.NoAi}. A {@link com.xpdustry.shard.sim.unit.Player}
 * controls a unit through {@link com.xpdustry.shard.sim.unit.PlayerControl}; respawning at a core is the
 * {@link com.xpdustry.shard.sim.unit.PlayerSpawns} seam (WP23).
 *
 * <p>Status effects (WP42). {@link com.xpdustry.shard.sim.unit.Statuses} holds a unit's entries and applies effects in
 * vanilla's order (extend, react, add); {@link com.xpdustry.shard.sim.unit.StatusTable} is the world's effect stats
 * and transitions; {@link com.xpdustry.shard.sim.unit.StatusComponent} is {@code StatusComp.update}, which folds the
 * entries into the multipliers that movement, damage, weapons and building read ({@code StatusOracleTest}). Apply an
 * effect with {@code unit.statuses.apply(unit, effect, duration)}.
 *
 * <p>Porting a movement shape (WP44). {@link com.xpdustry.shard.sim.unit.MoveShape} is the base class, and it is the
 * flying shape: a class with no movement component, which {@code alpha} and {@code flare} hold to vanilla bit for bit
 * ({@code FlyingTraceOracleTest}). A shape is one vanilla movement component ({@code MechComp}, {@code LegsComp},
 * {@code TankComp}, {@code CrawlComp}, {@code WaterMoveComp}, {@code ElevationMoveComp}). Every v160.7 shape is
 * ported and held to vanilla bit for bit, its state and every random draw included ({@code ShapeTraceOracleTest});
 * {@code UnderwaterMoveComp}, which no v160.7 class has, is a flag on the naval shape. To port another:
 * <ol>
 *   <li>For each of its {@code @Replace} methods ({@code solidity}, {@code onSolid}, {@code floorSpeedMultiplier},
 *   {@code drownFloor}, {@code rotateMove}) and each method it adds to ({@code moveAt}, {@code approach},
 *   {@code add}, {@code destroy}), override the {@code MoveShape} hook of that name in the shape's subclass. The base
 *   holds {@code UnitComp}'s version; an overriding hook replaces it, a {@code before...} hook runs first.</li>
 *   <li>Port its {@code update()} as a registered {@link com.xpdustry.shard.sim.unit.UnitComponent} named after the
 *   component, with its {@code @MethodPriority}, applying to the shape's locomotion, as
 *   {@link com.xpdustry.shard.sim.unit.LegsComponent} does. javap the generated class to check where the merge puts
 *   it.</li>
 *   <li>Its stored fields go on the subclass, with {@code copy}, {@code hash} and its checkpoint; values from the type
 *   go in {@link com.xpdustry.shard.sim.unit.UnitParams}.</li>
 *   <li>Keep every random draw a server makes, even for a sound or effect it never plays, when the draw is in an
 *   argument; drop drawing and code behind {@code !headless}.</li>
 *   <li>Gate it with a trace against vanilla on {@code ShapeTraceOracleTest}'s fixture (walls, deep, shallow and
 *   sand water, boulders, enemy buildings), and its synced fields with {@code ShapeSyncOracleTest}.</li>
 * </ol>
 *
 * <p>AI (WP54). {@link com.xpdustry.shard.sim.unit.AiController} is vanilla's {@code AIController}, targeting and
 * steering included; the kinds ({@code sim.ai}) register with {@link com.xpdustry.shard.sim.unit.AiKinds}, and read
 * their type's fields from {@link com.xpdustry.shard.sim.unit.AiType}. Bullets tell a hit unit's controller
 * ({@link com.xpdustry.shard.sim.unit.UnitController#hit}), which a command AI on an AI team counter-attacks.
 *
 * <p>Moving units (the seam for AI, WP54). A controller steers in {@code updateUnit}, which {@code UnitComp} calls after
 * the unit has moved this tick: {@link com.xpdustry.shard.sim.unit.Unit#movePref} (omni types {@code moveAt}, the
 * others {@code rotateMove}, which a mech turns its legs by), {@code moveAt}, {@code approach} to brake,
 * {@code lookAt} to aim the body, {@code updateBoosting} for types that can boost. Steering marks a mech's or tank's
 * step; a push ({@code impulse}, physics) doesn't. {@code speed()} already holds the floor, the shape's slowdown and the
 * boost; {@code canPass(x, y)} and {@code shape.solidity(unit)} say where a unit may stand, which is what a flowfield's
 * cost must agree with ({@code sim.path}).
 *
 * <p>Items and mining (WP45). A unit's stack is {@code ItemsComp}'s ({@link com.xpdustry.shard.sim.unit.Unit#addItem},
 * {@code maxAccepted}, {@code acceptsItem}, {@code clearItem}). Items move between units and buildings only through
 * {@link com.xpdustry.shard.sim.unit.ItemTransfers}, which runs each Call's handler and publishes its
 * {@link com.xpdustry.shard.sim.unit.ItemEvent} once, whoever moves them: a player's intents, a processor, a miner, an
 * AI. {@link com.xpdustry.shard.sim.unit.Miner} is {@code MinerComp} ({@link com.xpdustry.shard.sim.unit.MinerComponent}
 * runs it), with each type's mining fields in {@link com.xpdustry.shard.sim.unit.MineType}.
 * {@link com.xpdustry.shard.sim.unit.OreIndex} is {@code BlockIndexer}'s ore half, floor and wall ores in vanilla's
 * order, which {@code MinerAI} and {@code ulocate ore} read. They are held to vanilla by {@code MinerOracleTest} and
 * {@code PlayerItemsOracleTest}.
 *
 * <p>Owners: WP12 (ids, groups, order, fields), WP21 (unit core, lifecycle), WP42 (statuses), WP44 (movement shapes),
 * WP45 (items, mining), WP54 (AI).
 */
package com.xpdustry.shard.sim.unit;
