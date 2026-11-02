# Units: update order, movement, abilities, items and mining

Read before touching `sim.unit`, `sim.entity`, `sim.spatial`, `UnitComponent`s, movement shapes, abilities, item
transfers, mining, `Player`/`Players`, or unit save and sync codecs. Rows: W1.3, W2.2, W2.10, W3.7, W3.10, W4.1, W4.7, W5.1. Their owned
paths and WPs are in [`history.md`](history.md). Index, ownership rules and live contracts:
[`WAVES.md`](../../WAVES.md). Paths are relative to the repository root.

**Units (WP21 done in W2.2, WP44 in W2.10, WP42 and WP47 in W2.11, WP41 and WP43 in W2.8; WP54, 61, 62, 69)**
- Done: the unit update in vanilla's component order, lifecycle events in vanilla's Call order, `EntityGroups`
  (`Groups.sync`/`Groups.all`), `Player` and `Players`, the flying shape, `TileSolidity`, entity projections for
  every unit layout and `Player`, and `WorldWireContext`.
- BlockUnit is in (W2.16), without damage, death or team. LogicAI is W2.23's.
- Seams filled in 2b: `PlayerSpawns` (W2.12), `UnitDamage` (W2.8), `DropZones` (W2.11), `UnitContact` (W2.1).
- Done (W3.7, WP45 and WP70's first half):
  - unit stacks on `Unit`, with `ItemsComp`'s rules;
  - `ItemTransfers`: one `ItemEvent` and one Call per transfer, for players, processors, miners and AIs alike;
  - mining (`Miner`, vanilla's `MinerComp`) and `OreIndex` (BlockIndexer's ore half);
  - the miner, builder and repair AIs, with a follower sharing its leader's plan object through forks
    (`FollowerPlans`).

  `MinerOracleTest`, `BuilderOracleTest`, `RepairOracleTest` and `PlayerItemsOracleTest` are exact frame by frame,
  with their Calls and through saves. L5: `shard-mine.rig` passed 29/29 on Shard and on vanilla. `AiController.unit`
  is now stored, so a restored command AI doesn't rerun `init()`. `WorldPart.forked` is a new hook.
- Done (W3.10, WP62): all 15 ability kinds in `sim.unit.ability`, registered with `@AbilityKind`, with per-unit
  state in `UnitAbility` subclasses, their data in saves and `writeSync`, and every random draw a headless server
  makes. `AbilityOracleTest` (9 fixtures) and `UncarriedAbilityOracleTest` (the three kinds no vanilla unit carries)
  are exact for 600–800 frames, most through vanilla's save. All 15 breaks fail. The older unit fixtures now run
  abilities on both sides.
- Open from W3.10:
  - the quell fixture diverges in the random 66 frames after vanilla's save loads, with every compared field equal
    (likely the mender's load path; not diagnosed);
  - (closed by W6.8) the repair field's target cut-off and the energy field's ties follow vanilla's quadtree:
    `SpatialIndex.nearby(team…)` and `nearbyAnyTeam` now walk the team trees in their own order, which
    `SpatialQueryOracleTest` pins;
  - `unitSpawn` carries the unit as it is after the tick, and the next snapshot corrects it (W4.7's oracle compares
    `unitSpawn` Calls by target and name only for this reason);
  - requests: content-extract to emit every ability in vanilla's `ClassMap` (and `StatusFieldAbility.effect` isn't
    client-only); one `Units.canCreate` for `Build` and `WorldStatements` too (done by W4.1).
- Open from W3.7: wall-ore mining (Erekir's `mineWalls`), the `assist` command and the miner's per-item stances have
  no fixture; the builder's prebuild fallback (WP82; done by W4.7: `PrebuildAi`, `ai-paths.md`).

**Unit factories and reconstructors (W4.1 done: WP66)**
- Done:
  - `UnitFactory` and `Reconstructor` (on a shared `UnitBuild` base) in `sim.blocks.units`: ground, air, naval and
    Erekir factories and the reconstructors, with codecs for revisions 0–3 and the `unitBlockSpawn` Call;
  - `Units.canCreate` and `Units.getCap` are the one unit cap: `Build`, `WorldStatements`, payloads, `UnitSpawnAbility`
    and `TeamUnits` all call them;
  - `ObjectiveState.unitCreated` counts game-stats `unitsCreated`; `Building.getCommandPosition`/`onCommand` are the
    rally hooks (also used by `PayloadSource` and W4.2's `commandBuilding`);
  - `PayloadBlock.pushOutput` returns the last push vector and `moveOutPayload` uses it (Q219, `payloads.md`).
- Gates: `UnitBlockOracleTest` is exact frame by frame through vanilla's save: Serpulo 2,400 frames, Erekir 1,200, and
  the cap, configs and rally fixture 900 (units are built at 27.5 times in the oracle). 23 of 23 breaks fail, with the
  clean baseline, in one run on one head at build speed 27.5 (`scripts/breaks/w4.1-unit-blocks.py` on `runner.py`,
  `--no-build-cache`; W4.10, review 43). Each failure's first message is at its break: the factory's and
  reconstructor's state lines, their save chunks, the `unitBlockSpawn` and cap Calls, and the random for the pitch
  draw. Two show first in the power graph, where the consumption they change appears. L3. L5: `shard-factory.rig` 27/27 on Shard and 25/25 on vanilla.
- Quirks kept: Q244, Q245. Deviations: none.
- Covered by W5.8: the reconstructor's front-side refusal and its banned and unresearched branches
  (`ReconstructorRefusalOracleTest`, exact through vanilla's save; a banned upgrade or unit is then held for good, as
  `Units.canCreate` refuses it; an AI team builds what nobody researched).
- Gaps: `unitsCreated` has no oracle; effect and sound calls are ported only as their random draws; vanilla's `UnitFactory` has no stance
  config on v160.7, so there is none to port.
- Open (perf, after the PoC; `WAVES.md` backlog): `TeamUnits.capModifier` rescans every building and allocates on each
  `Units.canCreate`, where vanilla keeps the sum incrementally (`BlockIndexer.addIndex`/`removeIndex`).

**Unit assembler (W5.1 done: WP81)**
- Done:
  - `UnitAssembler` and `UnitAssemblerModule` in `sim.blocks.units` (vanilla's `UnitAssembler.java` and
    `UnitAssemblerModule.java`): plans by module tier, the area and its occupancy check, payloads thrown into the area
    and counted in `PayloadSeq`, four drones per assembler (tethered, `BuildingTetherComp`), the rally point, the cap;
    codecs (revisions 0–1 and 0), the `assemblerDroneSpawned` and `assemblerUnitSpawned` Calls (`AssemblerEvent`,
    `AssemblerCallMapping`), and the placement rules (`Build.canPlaceOn`'s `UNIT_ASSEMBLER` and `UNIT_ASSEMBLER_MODULE`);
  - `sim.ai.AssemblerAi` replaces the inert `assembler-ai` stand-in.
- Gates: `AssemblerOracleTest` is exact frame by frame through vanilla's save, Calls byte for byte, save chunks at
  the load and the end, block snapshots every 300 frames: the tank, ship and mech assemblers with modules, fed by
  payload conveyors and powered, building tier 0 and tier 1 units with their drones (3,000 frames, save at 1,300); and a
  tank assembler's area held by a wall and by a dagger, then the cap reached mid-build, through the save, and the
  drones despawning with their assembler (1,700 frames, save at 900). `AssemblerPlacementOracleTest` holds `canPlaceOn`
  and `validPlace` to vanilla's on every tile and rotation of a 64x64 map. `UnitAssemblerTest` forks and checkpoints
  mid-build. `LivePlacedOracleTest` builds all four by construction. Breaks: `scripts/breaks/w5.1-assembler.py`.
  Qualified (review 45): the save and snapshot bytes are equal with vanilla's `PayloadSeq` pinned to first-added order
  (`UnitBlockRun`); an unpinned vanilla writes identity-hash order (`docs/architecture/tier-exceptions.md`).
  `RotatedAssemblerOracleTest` (W6.5) covers rotation, exact frame by frame with the same pins, through vanilla's save:
  a tank assembler facing north with two modules a data patch makes tier 2 (a tier gap: the tier stays 0, the progress
  isn't reset, the tier 1 payloads wait), its drones killed by a `setMapArea` that leaves it outside (`allowUpdate()`
  false) and remade once the area is whole (1,100 frames, save at 500); and one facing west whose vanquishes go into a
  payload void on the area's centre and are never added (1,600 frames, save at 800).
- Every vanilla block is now ported. Tests that need an unported block use a probe kind: `VanillaWorlds.withKind` in
  `:sim`'s tests, `LivePlacedOracleTest.without` in the oracles.
- Quirks: `PayloadSeq` saves in identity-hash order in vanilla; Shard keeps first-added order and the oracles pin
  vanilla's map to it. `powerWarmup` lerps from the power status (kept). `overlapsTile` reads its radius from the last
  physics step (Shard: 1 tile). After a tier change, the old tier's payloads stay on their conveyors for good (kept).
- Gaps: a tier gap needs patched content (vanilla's one module is tier 1), so no fixture mixes module tiers.

**Units: further open items and shared APIs**
- `UnitSink` creates a carried unit after reading its body, so on load its id and constructor draws come after
  its passengers'; `PlayerSink` takes control with `Player.unit(...)`; posted cap/env destroys run after `COMMANDS`.
- `BuildPlan.place` doesn't zero a non-rotating block's rotation (`planRotation`, WP32). CommandAI stance
  conflicts aren't resolved on read. Building payloads are untested until a building codec exists.
- Movement (W2.10 done): mech, legs, tank, crawl, naval, hover and boost bit for bit for 600 frames; crowd pushes
  exact (`BodyTree`); wet water too, since W2.11. Open: the oracle gates avoid unit types with abilities (vanilla's
  neoplasm `LiquidExplodeAbility` is stripped in `ShapeTraceOracleTest`) (WP62: oxynoe's overclock, pulsar's field); underwater is
  read from source only (no v160.7 class uses it); breaking a prop doesn't publish the game-stats `BlockBuildEndEvent`.
- Shared unit APIs: `Unit.impulse`/`impulseNet` (knockback, conveyors, core spawn, wind) and `Unit.unloaded()`
  (legs reset with random draws; call it wherever vanilla does).

**Unit seams (from the review 38 routing, done)**
- W2.8: ordered seams in the unit update for abilities (between env death and drag) and death weapons (between
  the explosion and crash damage).

Contracts for ores, item transfers and unit items (`OreIndex`, `ItemTransfers`, `Unit.addItem`): "Contracts for wave 3"
in [`contracts.md`](contracts.md).
