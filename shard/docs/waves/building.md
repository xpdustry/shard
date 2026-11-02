# Building base, construction, cores and teams

Read before touching `sim.building`, `sim.build`, `sim.team`, `sim.blocks.storage`, placement rules, build plans, config
dispatch or the building lifecycle hooks. Rows: W1.2, W2.1, W2.12, W2.15. Their owned paths and WPs are in
[`history.md`](history.md). Index, ownership rules and live contracts: [`WAVES.md`](../../WAVES.md). Paths are relative
to `shard/`.

**Building base (WP20 done in W2.1)**
- Done: consumers and efficiency, item and liquid movement, damage and death hooks, config dispatch (`Configs`),
  logic `enabled`/`lastDisabler`, `status()`, `checkSolid`, `visibleFlags`, `UnitContact`, the lifecycle hooks,
  `allowUpdate`, legacy removal, `BlockIndex`, `BuildingTargets`, vanilla's Rand and entity-id draws at creation
  (`ConstructionDraws` is gone), `BuildingCodecs#readBody` in `WorldWireContext`, and the Router family as the
  reference (porting guide in `sim.blocks`' package doc).
- 2b and later: fires, explosions and puddles from destruction (named hook); storage linking at map end (WP23);
  payloads carrying buildings; controlling a block through its block unit (WP60, WP26); a wall codec (defence family);
  `Reads#slice` in `common.io`; typed `:content` getters for the fields still read raw.
- Vanilla builds proximity in identity-hash order; `tools/oracle`'s `VanillaOrder` pins it in every oracle JVM.

**Construction (W2.15 done: WP30)**
- Done: `sim.build`, which covers:
  - the placement and break rules;
  - construct sites (progress, accumulators, core items, refunds, damage);
  - `constructFinish` then `placed()`, `overwrote` (conveyors, the battery, cores, storage) and `planRotation`;
  - the builder `UnitComponent` and team rebuild plans, newest first.

  The `BuildEvent` records map to their Calls in `BuildCallMapping`. Construct sites save as vanilla's chunk, and
  team plans load and save. `ConstructionOracleTest` compares everything, Calls included, every frame for its four
  fixtures (WP30, M0, rebuild, overwrote), with vanilla's place-sound clock pinned to Shard's ticks (ARCHITECTURE
  §1, wall-clock and thread timing). `rig/scenarios/shard-build.rig` passes with two stock clients.
- Open:
  - Vanilla's other build events (`BlockBuildBeginEvent` and the like) aren't published.
  - Unported placement rules (unit assembler) are refused. Closed by W5.1: the assembler's and module's rules are
    vanilla's (`AssemblerPlacementOracleTest`, `units.md`).
  - The journal's cause is the team, not the player; `lastAccessed` isn't ported.
  - Breaking plans carry no block, and team-plan configs that point at a building or unit become null.
  - These aren't oracle-gated: derelict repair, core upgrade, instant build, floors and overlays, rotating in place.
  - The place sound's random draw counts ticks; vanilla times it by wall clock, so under load it can land a frame
    apart.
- For stage 3:
  - Scripted builds: `Builder.addBuild(unit, BuildPlan.place(x, y, rot, block, config), true)` or
    `BuildPlan.breaking(x, y)`. Validity checks: `Build.validPlace` and `validBreak`.
  - Rebuild AI: `Teams.of(world).get(team).plans()`. Turrets get `sim.build.PlaceEnded`.
  - An unported block finished by construction streams and saves as vanilla's (`LivePlacedOracleTest`).

**Cores, storage and teams (W2.12 done: WP23)**
- Done: cores take, burn and share items, storage links to cores, team data (`StorageOracleTest`, 800 frames);
  player respawn at vanilla's best core for all six core blocks (`CoreSpawnOracleTest`); `CheckGameState` (game over,
  only after `Play`); `Teams` is the spawner's `WaveCores`; the unloader respects linked storage; `TeamUnits.recount`
  338 → 2 ns with no units (`TeamUnitsBench`).
- Open: `destroyToDerelict` and team clean-up after losing cores; no event yet for `setTeams` to derelict (it calls
  `Tile.reflowPower`).
- For stage 2: `Teams.of(world).core(team).items` (with `storageCapacity`), `Core.handleStack`,
  `Teams.anyEnemyCoresWithinBuildRadius`; `Teams.get(team).noCores()`/`isAlive()`, `getActive(world)`,
  `isActive(world, team)`, `Core.lastDamage()`, and the `CoreEvent`/`GameEvent` records.

**Building events (from the review 38 routing, done)**
- W2.9: `BuildingEvent.Configured` publishes a deep immutable copy of its value (`byte[]`, `Point2[]`, nested).
