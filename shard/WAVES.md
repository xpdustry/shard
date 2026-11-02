# Waves and ownership

Who owns which paths in which wave, the contracts between areas, and what is still open. Paths are relative to
the repository root. In a source tree, `sim.tile` means `sim/src/*/java/com/xpdustry/shard/sim/tile/`. Work package (WP) numbers
refer to note 26's inventory of the vanilla scope.

This file is the index. Each area's state (done, open, deviations) is one hop away in `docs/waves/<area>.md`, the
finished wave tables and narrative are in `docs/waves/history*.md`, and what still binds every agent is below. Read the
ownership rules and contracts, then open only the area files your work touches.

**Ownership rules:**
- You edit only your owned paths.
- Outside them, edits are append-only: registration lines, catalog entries, a `ModuleRules` row.
- Everything else goes into "Requests" in your report.
- Classes in someone else's package are contracts. You may use them, and you add to them only through a request.
- `:content`'s API grows additively: add typed getters, don't rename.

## Area files: state and open items

Each file below holds an area's done, open and deviation notes, for every wave.

| File | Covers; read when |
|---|---|
| `docs/waves/building.md` | building base, construction (`sim.build`), cores, storage and team data. Before touching `sim.building`, `sim.build`, `sim.team`, `sim.blocks.storage`, build plans, config dispatch |
| `docs/waves/transport.md` | conveyors, ducts, sorters, unloaders, bridges, mass driver. Before touching `sim.blocks.transport` or `sim.blocks.distribution` |
| `docs/waves/production.md` | drills, pumps, conduits, crafters, separators, heat. Before touching `sim.blocks.production`, `.liquid`, `.crafting`, `.heat` |
| `docs/waves/power.md` | power graphs, generators, reactors. Before touching `sim.blocks.power` or reading `efficiency` |
| `docs/waves/defense.md` | walls, doors, mines, projectors, radar, repair and build towers, legacy blocks. Before touching `sim.blocks.defense`, `.sandbox`, `.units` |
| `docs/waves/combat.md` | bullets, weapons, damage, turrets, block units, fires, puddles. Before touching `sim.bullet`, `sim.combat`, `sim.blocks.turret`, `sim.fire`, `sim.puddle` |
| `docs/waves/units.md` | unit update order, movement, abilities, items, mining, `Player`. Before touching `sim.unit`, `sim.entity`, `sim.spatial` |
| `docs/waves/waves.md` | wave spawner, countdown, drop zones, statuses, weather. Before touching `sim.waves`, `Statuses` |
| `docs/waves/ai-paths.md` | wave and support AIs, `CommandAi`, flowfields, the RTS `ControlPathfinder`. Before touching `sim.ai`, `sim.path` |
| `docs/waves/fog.md` | fog of war: `FogControl`, fog radius, static fog, team assignment on join. Before touching `sim.fog`, `Building.fogRadius`, `StaticFog` or fog filtering in entity snapshots |
| `docs/waves/pvp.md` | PvP: team assignment and `TeamAssigner`, a dead team's clean-up, the world pause, `pvpAutoPause` and the empty-server `autoPause`, `CoreChangeEvent`, the console's `save`/`load`. Before touching `HostInputs.Join`, `sim.team.DeadTeams`, `World.paused`, `PvpAutoPause`, `EmptyAutoPause` or `server.game.Saves` |
| `docs/waves/payloads.md` | payload model and blocks. Before touching `sim.blocks.payload` or anything that carries buildings or units |
| `docs/waves/processors.md` | logic VM, processors, world processors, unit statements. Before touching `logic/**`, `sim.blocks.logic`, `sim.logic`, `sim.ai.LogicAi` |
| `docs/waves/objectives.md` | objectives and markers. Before touching `sim.objectives` or `ObjectivesJson` |
| `docs/waves/maps.md` | map loading, filters, msav, the L3 map gate, the load clock. Before touching `MapLoader`, `compat.v160.msav`, `.world`, `.filters` |
| `docs/waves/wire-content.md` | wire schema, codecs, content pack, Patchgen. Before touching `wire/**`, `content/**`, `tools/content-extract`, `tools/wirelift` |
| `docs/waves/server.md` | connect flow, hosting, hardening, tick cost, world loop. Before touching `server.host`, `.session`, `.sched`, `.world`, `net/**` |
| `docs/waves/sync.md` | intents, snapshots, Calls, `Outbox`, action filter, open review 39/40 items. Before touching `server.sync`, `server.intent`, `compat.v160.sync` |
| `docs/waves/game-admin.md` | game over, chat, menus, kicks, bans, console. Before touching `server.chat`, `.game`, `.ui`, `.admin`, `.console` |
| `docs/waves/replay.md` | replay bundles, view format, `shard replay`, viewer. Before touching `replay/**`, `tools/replay` |
| `docs/waves/m0.md` | M0, M1 and M2 results, evidence, open items. Before changing `rig/scenarios/m0*`, `m1*`, `m2*`, `M0OracleTest`, `M2OracleTest` or `M0Run` |
| `docs/waves/contracts.md` | stage contracts of waves 2a–3: domain events, porting guide, targeting, one owner per concept. Before adding a kind, event, `CallMapping`, `UnitComponent` or cross-area API |
| `docs/waves/history.md` | finished wave tables: row ids, owned paths, WPs; wave narrative. When a comment cites a row id and you need its owner or scope |
| `docs/waves/history-reviews.md` | reviews 38–41 as routed, all ✓. When a comment or test names a review fix |

## Contracts

- **Tile changes:** `TileGrid#changes(TileChangeConsumer)`. Changes made during tick N are pending; `beginTick()`
  turns them into tick N+1's list, which every reader iterates without consuming, and `endTick()` clears it. Each
  reader sees each change once, at most one tick late. Code that must react in the same tick (processor links)
  is notified synchronously by `Buildings` instead. The read rule is in `sim.tile`'s package comment.
- **Kind keys:** `KindRegistry.key(block)` is `kind()` for tile kinds and `buildKind()` for buildings (the vanilla
  class that declares the building). Codecs and adapters are keyed the same way.
- **Vanilla-content worlds:** `WorldConfig.of(Epoch.vanilla(), …).withKinds(KindRegistry.builtIn().withPassthrough())`
  until every kind is ported. Unported buildings are `sim.building.PassthroughBuilding`s carrying their save
  bytes, revision and source epoch. Map loaders go through `beginLoad`/`setBlock`/`endLoad` and call
  `PathService.invalidateAll()` after `endLoad`.
- **Content:** `:content` owns every definition. Read typed getters; if one is missing, add it to `:content` rather
  than reading `fields()` by name across a module.
- **Checkpoints:** every field is stored or `@Derived` (`ARCHITECTURE.md` §4). `CoverageTest` fails otherwise; fix it
  with a serializer, never an exclusion. Large self-saving parts use a `SectionPart`.
- **Intents:** the transport builds `IntentEnvelope`s into the session's `Mailbox` (bound 64; only `clientSnapshot`s
  coalesce; a reliable intent over the bound is refused and kicks). Sessions reach a world only through its
  `WorldMailbox`. The world host calls `mailbox.pump()` right before `world.tick()`; pumping is round-robin with a
  quantum of 16, and intents never take the inbox's last eighth, except that a sealed (closing) mailbox drains its
  remaining intents as control (`Mailbox.java:17`). Join and leave are control entries outside the
  bound; a leave stays in its mailbox until the inbox takes it. Wake a sleeping world with `WorldMailbox.signal`.
- **Golden hash:** when `HashTest.GOLDEN` moves, show with a toggle that only the intended state changed, and say
  why in the commit. A toggle proof covers only the state its fixture holds: a field added to a kind the golden world
  lacks (W4.3's `TurretBuild.lastRangeChange`) needs a fixture with that kind (review 43).
- **Merging:** run the full `scripts/vm-gradle.sh check` after every merge. Branches that are green alone can break
  together (wave 1 had three such cases).

Stage contracts (domain events, porting guide, targeting, one owner per concept): `docs/waves/contracts.md`.

## Backlog

### Later
- The unit assembler (WP81), team AIs (WP82), PvP (WP83: review `HostInputs.team` first, `docs/waves/fog.md`).
- Bound world-processor Calls per world (Q243, `docs/waves/sync.md`): only the 256 KiB write cap bounds them now.
- A clock seam in `Server.Options` or `Transport.Config`, so `ServerTest.aQuietClientOutlivesTheReadTimeout` stops sleeping
  14 s (W4.8, `docs/waves/server.md`).
- Map patches and assets (84); client RPC fuzzing (93).
- Perf, after the PoC (compatibility first, 2026-11-01): W4.6 cut tick allocation 67% (`docs/waves/server.md`).
  Next sites: WireGen's unit `Source` needs an indexed mount accessor (`Codecs.Mount` per mount); `KindKey.of`'s regex in
  `BlockDef.ownsBlockUnit`; the autosave fork (a pooled copy-into fork); `TeamUnits.capModifier`, which rescans every
  building and allocates on each `Units.canCreate` (vanilla keeps the sum incrementally, W4.1); the oracle JVMs' flags
  (JIT and GC threads, `ShardExtension`, W4.9).
- Wave 5 composition (81–84; WP80 is W4.4's), then wave 6 soaks (90–95).

## Row ids

Every row id: its state and open items are in the file below, and its row (owned paths, WPs) in `docs/waves/history.md`.
Grep the id there (RP2 has no row: its text is in `docs/waves/replay.md`).

| File | Row ids |
|---|---|
| `docs/waves/building.md` | W1.2 W2.1 W2.12 W2.15 |
| `docs/waves/transport.md` | W2.6 W3.1 |
| `docs/waves/production.md` | W3.2 W3.3 W3.4 |
| `docs/waves/power.md` | W2.7 |
| `docs/waves/defense.md` | W3.5 W3.6 W4.7 |
| `docs/waves/combat.md` | W2.8 W2.16 W2.18 |
| `docs/waves/units.md` | W1.3 W2.2 W2.10 W3.7 W3.10 W4.1 W5.1 |
| `docs/waves/waves.md` | W2.11 |
| `docs/waves/ai-paths.md` | W1.11 W2.17 W3.11 W4.2 W4.7 |
| `docs/waves/fog.md` | W4.3 |
| `docs/waves/pvp.md` | W5.3, W6.4 |
| `docs/waves/payloads.md` | W3.9 W4.1 W4.4 |
| `docs/waves/processors.md` | W1.10 W2.9 W2.23 |
| `docs/waves/objectives.md` | W3.12 |
| `docs/waves/maps.md` | W1.7 W2.3 W2.19 |
| `docs/waves/wire-content.md` | W1.1 W1.5 W2.5 |
| `docs/waves/server.md` | W1.4 W1.6 W2.4 W2.4h W2.21 W4.5 W4.6 W4.8 W4.9 |
| `docs/waves/sync.md` | W2.13 W2.14 W2.20 W2.25 |
| `docs/waves/game-admin.md` | W1.12 W3.8 W3.13 |
| `docs/waves/replay.md` | RP2 |
| `docs/waves/m0.md` | W2.22 W5.6 |
| `docs/waves/history.md` | W1.4i W1.6h W1.8 W1.9 I1 I2 J27 W2.24 (their text is the table row or the narrative) |

Section names that other files cite:

| Cited as | Now in |
|---|---|
| "Domain events, not Calls", "Contracts for 2a", "2b", "2c", "wave 3" | `docs/waves/contracts.md` |
| "Leftovers", "Map hosting" | `docs/waves/maps.md` |
| "Turrets", "Bullets and weapons" | `docs/waves/combat.md` |
| "Paths", "Wave AI" | `docs/waves/ai-paths.md` |
| "Intents" (state; the Mailbox rule stays under Contracts above) | `docs/waves/sync.md` |
| "Wave 1 and 1.5", "Wave 2a" to "Wave 2d", "Wave 3a", "Wave 3b" | `docs/waves/history.md` |
| W1.4's coverage gate | "Checkpoints" under Contracts above |
