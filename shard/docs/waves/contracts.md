# Stage contracts

The contracts of waves 2a–3 that still bind new work: domain events, the porting guide, hash toggles, targeting,
construction and AI seams, one owner per concept. Read before adding a kind, an event, a `CallMapping`, a
`UnitComponent` or any API another area calls. The merge coordination that is finished (2a's row hand-offs, 2b's
`IntentCall` → `Outbound` and off-limits notes) is in [`history.md`](history.md). The always-live contracts (bus, tile
changes, kind keys, checkpoints, intents, golden hash, merging) are in [`WAVES.md`](../../WAVES.md). Paths are relative
to `shard/`.

**Contracts for 2a:**
- Domain events, not Calls: both rows `world.publish` `WorldEvent` records in the order vanilla sends its Calls.
  The sync layer (2b) turns them into Calls; oracle gates compare that order with vanilla's captured Calls.
- `sim.world` is shared: additive changes only, each one named in the report.
- Wire changes W2.2 needs go to W2.5 as requests; generated code changes only through wirelift.

**Contracts for 2b:**
- Follow the porting guide in `sim/blocks/package-info.java` (kinds, `@Derived`, coverage, codecs, events, oracle
  tests) and W2.2's in `sim/unit/package-info.java` (`UnitComponent`, `MoveShape`).
- Unit behaviour is added by registering a `UnitComponent` or a `MoveShape`, not by editing `Unit.update`.
- Checkpoint new state with a serializer or `SectionPart` in your own package; `sim.persist.Foundation` gets
  additive lines only, named in the report.
- `HashTest.GOLDEN` moves on your branch with a toggle proof; the lead re-derives it at merge.
- Additive typed getters in `content/**` and new files in `tools/oracle` are open to every row.
- `WorldEvent` records belong to the package that publishes them, in vanilla's Call order.
- Tile events map to no Call: a `TileEvent` only observes a committed change, so it can't say which operation
  caused it. An operation that vanilla sends as a tile Call (`setNet`/`removeNet`, core capture, `setTeam(s)` to
  derelict, `constructFinish`) publishes its own event in its package. W2.13 maps events to Calls with one
  `@Register CallMapping` per family in `compat.v160.sync`.
- `Session.loadGeneration()` (W2.4h) is bumped at each `worldDataBegin` and each stream start; per-join buffering
  (W2.13) builds on it. W2.13 edits only send call sites in `server.host`; W2.4h owns its structure.
- Intents (W2.14): `ConnectFlow.received` only builds an `IntentEnvelope` into the `Mailbox`; `ActionFilter`s run on
  the world thread. Replies and forward echoes are `server.intent.IntentCall` records that carry their recipients.
  A forward is `sendExcept(sender)` when the Call's `called` includes the client, otherwise it goes to everyone
  (`CallGenerator.java:328`). The server-side `SyncComp` is W2.14's `UnitComponent`.

**Contracts for 2c** (the 2b contracts still hold):
- **Targeting:** unit queries live in `sim.spatial.SpatialIndex` (`closestTarget`, `closestEnemy`, `bestEnemy`).
  W2.16's building half is on main (`0c5ec77`, `TargetingOracleTest`): `Targets.closestTarget`, `bestTarget`,
  `findEnemyTile`, `findAllyTile`, `findTile`, `closestBuilding`, in `Buildings.tree` order. W2.16 also moves
  `ChunkTargets` (W2.8's weapons and homing) onto it.
- **Fires:** other rows call W2.8's `Hazards.of(world)` (`fire(tile)`, `hasFire(x, y)`, `extinguish(tile,
  intensity)`), which is already on main; W2.18 fills it with `sim.fire.Fires`, so callers need no stand-in.
- **Construction and AI:** a builder reads `unit.plans` (W2.14 fills it from snapshots); AI-built plans (rebuild)
  push to the same queue. Construction events (`beginPlace`, `beginBreak`, `constructFinish`, `deconstructFinish`)
  are records in `sim.build`; W2.13 maps them to Calls.
- **Timers:** AI timers count ticks (`sim.world.Timers`). W2.19 owns the building-timer-after-load model; W2.17 only
  reads `Timers`.
- **Rig:** new scenario files may go in `rig/scenarios/`; the rig's code is W2.4h's. Client gates that need entity
  snapshots run after W2.13 merges, or on a scratch merge of W2.13's branch that is never committed (as W2.14 did).
- **Hash:** every row moves `HashTest.GOLDEN` with a toggle proof; the lead re-derives it at merge.

**Contracts for wave 3** (one owner per concept; the row that merges second removes the duplicate):
- **Ores:** `sim.unit.OreIndex` (W3.7, merged): floor and wall ores, with counts, in BlockIndexer's quadrant order.
  `LogicUnits` calls it.
- **Item transfers:** `sim.unit.ItemTransfers`, `ItemEvent` and `ItemCallMapping` (W3.7), for processors, players
  and AIs alike (merged). `ItemCallsTest` checks each transfer sends exactly one Call.
- **Unit items:** `Unit.addItem`, `maxAccepted`, `acceptsItem`, `clearItem` (W3.7).
- **Item bridges:** `sim.blocks.distribution.ItemBridgeBuild`, an abstract base that isn't a kind, with
  `updateTransport` and `doDump` hooks, its range from `bridgeRange()`, and
  `compat.v160.blocks.distribution.ItemBridgeBodies` (W3.1, merged). W3.4's liquid bridges extend it.
- **Liquids:** W3.4's first kinds merged early (`2afcfabd`), so W3.2 and W3.3 can use them:
  - `LiquidSource` and `LiquidVoid`;
  - conduits: plain, pulse, plated and reinforced;
  - the liquid router, container and tank;
  - the liquid junction and the reinforced bridge conduit.

  `LiquidOracleTest` is exact for 600 frames per fixture, plus 120 after vanilla's save. The merge also fixes
  `Combat.Splash`: a destroyed liquid block's puddle lands at whole pixels, as vanilla's `Mathf.range(int)` does.
- **`playerPlaced`** runs only on the placing client (VANILLA-QUIRKS Q126). Kinds don't port what only it reaches.
