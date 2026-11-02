# AI, flowfields and the RTS control pathfinder

Read before touching `sim.ai`, `sim.path`, `AiController`, `CommandAi`, wave or support AIs, flowfields, avoidance, or
`ControlPathfinder`. Rows: W1.11, W2.17, W3.11, W4.2, W4.7. Their owned paths and WPs are in [`history.md`](history.md). Index,
ownership rules and live contracts: [`WAVES.md`](../../WAVES.md). Paths are relative to the repository root.

**Wave AI (W2.17 done: WP54)**
- Done:
  - `sim.unit.AiController`, vanilla's `AIController`: targeting through `Targets`, weapons, `moveTo`, `circle`,
    `pathfind` and timers on `Timers.time()`.
  - The wave AIs: ground, flying, suicide, hug, flying-follow, defender, missile and boost. `AiKinds` registers them
    by name and `Controllers` makes them with vanilla's timer draws.
  - CommandAI's idle targeting, move command, queue and counter-attack; `UnitController.hit(unit, owner)`.
  - `sim.path.AvoidanceProcess`, and `WaveFields` preloading the wave team's fields.
  - Gates, exact frame by frame against vanilla with its pathfinder run synchronously each frame and its physics
    random pinned to Shard's (ARCHITECTURE §1, wall-clock and thread timing):
    - `WaveFightOracleTest`: 900 frames;
    - fortress's first wave walking to the core and hitting it (`FirstWaveOracleTest`): 3,700 frames;
    - `WaveMapsOracleTest`: three bundled maps at wave 5, 1,800 frames each;
    - `AvoidanceOracleTest`: two ground teams.

    `rig/scenarios/wave-ai.rig` passes 13/13 on both Shard and vanilla, on fortress only; the brief's three maps are
    oracle-gated instead (`WaveMapsOracleTest`).
- Deviations: CommandAI's ground moves use Shard's flowfields (Tier D), because `ControlPathfinder` isn't ported
  (closed by W4.2: `CommandAi` runs on it). `WaveFields` reads the game's rules, not the map file's.
- Open:
  - BuilderAI, MinerAI, RepairAI, CargoAI, AssemblerAI and LogicAI are inert stand-ins that still draw their timers
    (WP69, WP70). Closed: the builder, miner and repair AIs (W3.7), LogicAI (W2.23), CargoAI and PrebuildAI (W4.7).
    AssemblerAI: closed by W5.1 (`AssemblerAi`, `units.md`).
  - Formations, `UnitGroup`, payload commands and stances aren't ported (WP72). Closed by W4.2.
  - `isPathImpassable` and unreachable targets for RTS teams are missing. Closed by W4.2.
  - There's no naval map gate, and no legged unit in the avoidance fixture.
- For stage 3:
  - `ucontrol`: `AiController.moveTo`, `pathfind`, `circle`, `target`, and a `logic-ai` kind in `AiKinds`, plus
    `Unit.controller(c)` and `resetController()`.
  - The M0 wave: `Waves.runWave(world)` spawns units with their type's controller.

**The RTS control pathfinder (W3.11 done: WP71)**
- Done: vanilla's `ControlPathfinder` in `sim.path`: clusters, portals, per-goal fields, per-unit requests,
  invalidation on tile changes and the raycast cache, run on the tick in `ControlFlush` (after `PathFlush`), with
  `PathfindQueue`. It is checkpointed whole (`control-paths`), copied and hashed. LogicAi's `pathfind` uses it. The
  query API (`pathPosition`, `isNearObstacle`, `raycastFastAvoid`, `pathImpassable`, `costId`) is posted for WP72.
- Gates:
  - path costs equal to vanilla's, at tolerance 0, on all 68 bundled maps for every cost type, with 1,000 seeded
    queries each;
  - invalidation updates the same clusters, in the same order, as vanilla;
  - 16 units' path positions are exact on every frame for 900 frames, with a wall built and removed;
  - at 100 worlds, a query costs p50 0.3 µs and p99 17 µs, and a world holds 270 KB;
  - forks and restores keep the hash every tick.
- Deviations: the timing is in ticks (ARCHITECTURE §9); requests run in insertion order, where vanilla uses identity
  order; a replaced request is dropped (Q201); the raycast cache sees every tile change (Q205).
- Open: the 68-map gate is tagged `slow`, and the default check runs 5 maps. (`CommandAi` was switched by W4.2.)

**RTS commands (W4.2 done: WP72)**
- Done:
  - `sim.ai.CommandAi` (moved from `sim.unit`), vanilla's `CommandAI` on `ControlPathfinder`: every command
    (`sim.ai.command.UnitCommand`) and stance (`UnitStances`), formations (`UnitGroup`, Arc's quadtree in
    `FormationTree`), the payload commands, `unitEnteredPayload`, unreachable targets and `isPathImpassable` for AI
    teams. The `PathService` stand-in and its 7.9 hitbox are gone.
  - The RTS intents run `Orders` after W2.14's filter; vanilla's static `queuedCommands` is a world part
    (`CommandBatches`); `commandBuilding` calls W4.1's `onCommand`.
  - A loaded unit attack target is looked up again once every entity is read (`readAttackTarget`, `afterReadAll`).
- Gates: `compat` `v160.command.*OracleTest` (11 fixtures, 700-1,200 frames each, five through a save, payload Calls
  byte for byte); `RtsIntentsTest` (one per RPC); `CommandAiTest` (forks and restores mid-order);
  `scripts/breaks/w4.2-commands.py`; `rig/scenarios/shard-commands.rig`.
- Pins in the oracle: vanilla's control pathfinder runs on Shard's pass schedule with no field budget on either side,
  and `mainExecutor` runs formations in the calling thread (vanilla's is a pool thread).
- Deviations: formations are computed at once (vanilla's are ready a pool thread later); `CommandBatches` is per world
  and bounded (64 points, 4,096 units a point) where vanilla's map is a JVM static that grows without bound (Q234).
  Both are tier exceptions (`docs/architecture/tier-exceptions.md`, Q236). In the oracle only, vanilla's control
  pathfinder runs on Shard's pass schedule with no field budget (`steps(0)`).
- Gates, in numbers: 11 oracle fixtures exact (700–1,200 frames, five through a save), under a pinned schedule: no
  control thread in vanilla, Shard's pass schedule, no field budget, a same-thread formation executor (review 43); 23 of 23 breaks fail;
  `rig/scenarios/shard-commands.rig` 24/24 on Shard and on vanilla (box-select, move order, hold fire). The golden
  moved from `0xc6b64a5e94e08c4e` to `0x07772ddc050b4c25` (`CommandBatches`; without it the hash is fog's).
- Unpinned outcome gate (W4.10, review 43): `MoveOutcomeOracleTest`, on `CommandRun.unpinned`. Vanilla runs with its
  control pathfinder thread (12 ms budget), its flowfield thread, `mainExecutor` formations and its own physics
  random; Shard runs with its production field budget; frames are paced at 60 Hz, with no frame-by-frame compare. On
  the walled map, a group has arrived when all its units are within 6 tiles of its target. The two sides' arrival
  frames must be within 60, and at frame 1,500 their centroids and spreads within 1 tile each. Two fixtures: the mixed
  ground and legged army (7 units, one group), and busy pathfinding (six groups of five daggers ordered in one frame to
  six targets, six fields at once through two gaps). Measured: arrivals 0-7 frames apart, centroids within 0.04 tiles,
  spreads within 0.05. The test asserts that every unit crossed, that vanilla's control thread ran and that its
  formations used its own executor. One break fails it (`scripts/breaks/w4.10-review43.py`, units kept twice as far apart).
  It proves outcomes under real scheduling, not frames.
- Quirks kept: Q235, Q237, Q238, Q239 (`payloads.md`).
- Open (W4.2):
  - the rig's stance step calls `setUnitStanceChunked` rather than pressing the key (a rebound `KeyBind` never fired on a
    stock client);
  - `ControlPathfinder`'s `Trigger.update` drops stale requests at once, where vanilla does it a frame later
    (`Time.run(0)`). It is W3.11's code and no fixture hits it.

**Cargo and prebuild AIs (W4.7 done: WP70, second half)**
- Done:
  - `CargoAi` (`cargo-ai`) and `PrebuildAi` (`prebuild-ai`, which a `BuilderAi` falls back to), `PrebuildSpawns`
    (`Logic.java:573`), all registered in `SupportAis`; the cargo blocks `UnitCargoLoader` and `UnitCargoUnloadPoint` in
    `sim.blocks.units`, with codecs and the `unitTetherBlockSpawned` Call (`CargoEvent.TetherSpawned`);
  - `BuildingTree.any` (Arc's `QuadTree.any`) and a public `Teams.activeView`.
- Gates: `CargoOracleTest` is exact frame by frame through vanilla's save: cargo 2,000 frames (save at 1,100), prebuild
  1,200 frames (save at 400). 21 of 22 breaks fail. `LivePlacedOracleTest` runs the loader and the unload point as
  built blocks; `CargoAiTest` forks and checkpoints. The fixtures pin the place sound's clock as `SupportRun` does (Q61).
- Quirks: Q246, Q247, Q248, Q249 (`defence.md`), all kept (`sortPlans` isn't ported).
- Deviations: `unitSpawn` Calls are compared by target and name only, because Shard serializes the unit after the tick
  (`units.md`).
- Open (W4.7):
  - covered by W5.8 (`CargoGapsOracleTest`, exact through vanilla's save): "stale points taken first" now fails it (a
    point is made stale while the manifold heads back with its index on it); the ore search starts from the unit; a
    destroyed unload point is flown to (vanilla's `CargoAI` never checks its target is valid);
  - `TileForkTest` and `TileCheckpointTest` churn a re-kinded probe; only 5x5 blocks are left unported.

**Paths**
- RTS fields use `ControlPathfinder`'s costs but aren't compared against it. `randomWaveAI` drops vanilla's
  `hashCode()` seed term outside wave mode. Neoplasm and the RTS unit walk aren't done.
- Tile changes made between `COMMANDS` and `PATH_FLUSH` reach the path flush one tick later (Tier D).

**Team AIs (W5.2 done: WP82)**
- `sim.ai.team`: `RtsAi`, `BaseBuilderAi`, `BaseRegistry`, `SchematicRotation`, and `TeamAis`, the world part that runs
  them on the `rtsAi` and `buildAi` team rules; vanilla's 210 base parts in `content.base` (`BaseParts`).
- Gates: `TeamAiOracleTest` (RTS squads at `rtsMinWeight` 1.2 and 6, 1,500 frames through a save) and the
  base-builder fixture are exact, pinned: `CommandRun`'s pins plus `TeamAiRun`'s, the control pathfinder's 1,000 ms
  recompute window set from Shard's ticks and `damagedSet` in first-hit order (review 45). `TeamAiOutcomeOracleTest`
  plays to game over on real schedules within stated tolerances, with vanilla's `damagedSet` unmodified (W6.1).
  `BaseRegistryOracleTest`, `TeamAisTest`.
- W6.1: bullets take their candidate units in vanilla's team-quadtree order (`sim.spatial.UnitTree`,
  `BulletOrderOracleTest`), which un-disabled `rtsSquadsWaitForTheirEstimateThroughASave`.
- Open: Erekir core units need `BuilderAI(true, 500)` for AI teams.
