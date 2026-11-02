# Wave history

The finished wave tables (row ids, owned paths, WPs) and the wave narrative. Read to find who owned a path, why a row
exists, or what a row id in a code comment means. The "WPs and open items" columns name the by-area sections of the old
file; those sections are now the area files listed in [`WAVES.md`](../../WAVES.md). Live contracts are in
[`contracts.md`](contracts.md); review routings in [`history-reviews.md`](history-reviews.md). Paths are relative to
`shard/`.

## Wave 1 and 1.5 (done)

| Id | Area | Owned paths | WPs |
|---|---|---|---|
| W1.1 | Content system and the vanilla pack | `content/**`, `tools/content-extract/**` | 10, 03 |
| W1.2 | Tiles, placement primitives, environment | `sim.tile`, `sim.building` (placement, proximity, passthrough), `sim.blocks.environment` | 11, 27 |
| W1.3 | Units, entity ids, spatial index | `sim.unit`, `sim.entity`, `sim.spatial`, `sim.bullet` (table, ids) | 12, 21 (base) |
| W1.4 | World loop, checkpoints, scheduler, lifecycle | `sim.world`, `sim.persist`, `server.sched`, `server.world` | 13 |
| W1.5 | Wire schema, codecs, lifter | `wire/**`, `tools/wirelift/**` | 14 |
| W1.6 | Transport, sessions, bots | `net/**`, `server.session`, `server.dev` (temporary), `tools/bots/**` | 15 |
| W1.7 | msav, world stream, rules | `compat.v160.msav`, `compat.v160.world`, `compat.v160.rules`, `sim.rules` | 22, 46 (model, JSON) |
| W1.8 | Scenario DSL and differential runner | `testkit/**` | 16 |
| W1.9 | Vanilla client rig on boat | `rig/**` | 17 |
| W1.10 | mlog VM | `logic/**` | 18 |
| W1.11 | Flowfields and the path service | `sim.path` | 53 (core) |
| W1.12 | Config, storage, admin model, ops skeleton | `server.config`, `server.store`, `server.admin`, `server.ops` | 50, 51 (parts) |

How it went:
- Rows merged as soon as they were green, not in the planned order. W1.4's coverage gate caught state that other
  rows added in parallel, so W1.4 was parked on a side branch until **W1.4i** wrote the missing serializers (tiles,
  buildings, paths, units, physics, spatial, bullets, ids, rules) and fixed review 36's blocker.
- Wave 1.5 replaced the stand-ins every row had built against: **I1** moved `:sim` onto `Epoch.vanilla()`, fixed the
  kind keys, the shared tile change list and the darkness divergence; **I2** moved `:compat`, testkit and `:logic`
  onto the epoch and the real grid, wrote the patch section and folded the `ContentType` enums.
- **W1.6h** fixed review 36's transport findings (connect/close race, handshake and frame deadlines, exactly-once
  leave, `sendStream` reservation) and added per-session mailboxes, at no measurable CPU cost on the load gate.
- Review 36 (note 36) narrowed Tier A where vanilla isn't reproducible with itself
  (`ARCHITECTURE.md` §1).

## Wave 2: the M0 vertical slice

The M0 description and result are in [`m0.md`](m0.md).

**Lesson from wave 1:** rows built in parallel against a seed API cost a whole integration wave. Wave 2 lands the
two interfaces everything else uses first (2a), then fans out the families on them (2b).

### Wave 2a: the interfaces, plus work that needs neither

| Id | Area | Owned paths | WPs and open items |
|---|---|---|---|
| W2.1 ✓ | Building base; Router as the reference family | `sim.building`, `sim.blocks.distribution`, item source and void in `sim.blocks.sandbox`, `sim.spatial.BuildingTargets`, `compat.v160.world`, `compat.v160.blocks`, `MapLoader` except the filter hook | 20; the "Building base" items except `TileSolidity`; the `PowerGraph` id draw |
| W2.2 ✓ | Unit core; alpha and flare as the reference flying shape | `sim.unit`, `sim.entity`, `sim.spatial` except `BuildingTargets`, `compat.v160.entity` | 21; `TileSolidity`; `WireContext`; the "Units" items except the `Time.time` model |
| W2.3 ✓ | Map generation filters, old building chunks | `compat.v160.filters` (new), `common.math` noise (additive), one filter hook in `MapLoader`, v1–3 reads in `msav` | the filter and v1–3 items of "Map hosting" |
| W2.4 ✓ | The real server: Main, connect flow, world send, console | `server/**`, `net/**`, `tools/bots/**`, additive `sim.path`/`sim.rules` for `PathRules` | 24 and the 50/51 wiring; the "Connect flow" items that need no new sim API. Starts after W1.6h merges. |
| W2.5 ✓ | Wire and content hardening | `wire/**` (hand-written), `tools/wirelift/**`, `tools/content-extract/**`, `content/**` | the "Wire and content" items except Patchgen's refusals |

**Contracts for 2a, merge coordination (fulfilled; the live ones are in [`contracts.md`](contracts.md)):**
- W2.1 owns building creation, so it draws vanilla's extra entity ids there (a turret's block unit, a power
  graph's updater) through `EntityIds.next()` and removes `MapLoader`'s load-time draw. It does not create block
  units or power graphs.
- W2.2 implements `WireContext` in `compat.v160.entity`, except `readBuildingBody`, which stays the default throw.
  W2.1 adds `BuildingCodecs#readBody(Reads, KindKey, int revision)`. The lead connects the two at merge.
- W2.1 ports the item source and void (`sim.blocks.sandbox`) as fixtures for the Router; nothing else in sandbox.

### Wave 2b: the M0 families

Stage 1 started before W2.4 merged: these rows need only the building base, units and map loading, which are on
main. The sync rows wait for W2.4's server API; construction, turrets, AI and the M0 scenario wait for stage 1.

| Id | Area | Owned paths | WPs and open items |
|---|---|---|---|
| W2.6 ✓ | Conveyors and routing | `sim.blocks.transport`, `sim.blocks.distribution` (not `Router`), `compat.v160.blocks.{transport,distribution}` | 31, the rest of 32 (junction, sorters, gates, unloaders); the six conveyor-read maps join L3 |
| W2.7 ✓ | Power | `sim.blocks.power`, `compat.v160.blocks.power`, power consumption in `sim.building.consume` | 37, 38; powered blocks in the placement gate; reflow on `changeTeam` |
| W2.8 ✓ | Bullets and weapons | `sim.bullet`, `sim.combat`, `sim.unit.WeaponMount` and a weapons `UnitComponent` | 41, 43; `UnitDamage`; replay accessors (bullet style, `isShooting`) |
| W2.9 ✓ | Processors | `sim.blocks.logic`, `compat.v160.blocks.logic`, `logic/**` | 67; the "Processors" items; `Processor.runScript` for `LogicFilter` |
| W2.10 ✓ | Movement shapes | new `MoveShape`s in `sim.unit`, `sim.spatial` physics | 44: mech, legs, tank, crawl, naval, hover, boost |
| W2.11 ✓ | Waves and status effects | `sim.waves`, `sim.unit.Statuses`/`StatusEntry` and a status `UnitComponent`, weather | 47, 42; `DropZones`; wave `WorldEvent`s |
| W2.12 ✓ | Cores, storage, team data | `sim.blocks.storage`, `sim.team`, `compat.v160.blocks.storage`, a `PlayerSpawns` implementation | 23; `Logic.play`'s effects (loadout, heal, wave time, saved units, storage linking) |

Started when W2.4 merged (J27 after the JDK call):

| Id | Area | Owned paths | WPs and open items |
|---|---|---|---|
| W2.4h ✓ | Server hardening (in W2.4's thread) | `server.host`, `server.session`, `server.connect`, `server.sched`, `net/**`, `rig/**` | review 38's server findings; class preloading; a clock in the scheduler; the L5 rerun |
| W2.13 ✓ | Snapshots, Calls and the `Outbox` | `server.sync`, checkpoint retention in `server.world`, snapshot encoding in `compat` | 25; events → Calls; per-join buffering; the replay `SYNC` hook; the proximity divergence measurement |
| W2.14 ✓ | `clientSnapshot`, intents and control | `server.intent`, the action filter | 26 |
| J27 ✓ | JDK 27 runtime, class files still release 25 | `build-logic`, `scripts/vm-gradle.sh`, the launcher's JDK check, the boat image | a go/no-go; oracle under 27 (`Unsafe`, `-XX:-UseLibmIntrinsic`); unchanged golden; load gate on 27; an AOT smoke test under Shenandoah and G1 (JEP 516) |

Stage 2 is wave 2c, below. The M0 scenario on boat follows once W2.13, W2.15, W2.16 and W2.17 are in.

**Contracts for 2b, fulfilled (the live ones are in [`contracts.md`](contracts.md)):**
- `IntentCall` → `Outbound`: whichever of W2.13 and W2.14 merges second makes the four Call records implement
  `Outbound` (To → `Audience.only(session)`, All → `ALL`, AllExcept → `except(session)`, Team → `Team`) and deletes
  `Intents.deliver`'s Call branches and `HostedWorld`'s `case IntentEvent`. `Kick` and `AdminRequested` stay.
- `server/**`, `net/**` (W2.4) and `replay/**` (the side quest) are off limits.

### Wave 2c: stage 2 of M0 (started 15:45Z, main `604b311`, JDK 27)

| Id | Area | Owned paths | WPs and open items |
|---|---|---|---|
| W2.15 ✓ | Construction | new `sim.build`, a builder `UnitComponent`, `compat.v160.build` | 30; `constructFinish` → `placed()`; `overwrote` (conveyors, battery); rebuild plans; `planRotation`; M0's conveyor line |
| W2.16 ✓ | Turrets | `sim.blocks.turret`, `compat.v160.blocks.turret`, building targets in `sim.combat.Targets` | 60; block units (player-controlled turrets); the readSync rotation quirk; M0's turret |
| W2.17 ✓ | Wave AI | `sim.ai`, the AI controller seam in `sim.unit`, `AvoidanceProcess` | 54; flowfield walking; the controller's `hit(bullet)`; `MissileAI`; basic `CommandAI` |
| W2.18 ✓ | Special bullets, fires, puddles | the WP61 kinds in `sim.bullet`, new `sim.fire` and `sim.puddle`, their projections | 61; fills `Hazards`; fire back on in five fixtures; the 23 weapon gaps |
| W2.19 ✓ | Leftovers | the files each item names | the open items listed in its brief, from W2.6–W2.12 and J27 |
| W2.20 ✓ | Review 39's fixes | `server.intent`, `compat.v160.sync`, `logic` config parsing, `sim.building.ConfigValue` | the double config forward (blocker); processor link-name bounds; every safe config value; the state snapshot's random and universe time |
| W2.21 ✓ | Tick cost | `server.sync`, finished rows' `sim` systems (others by request) | main's per-tick cost grew 2–4× since W2.4's load gate: profile it, split inherent from accidental, fix the accidental with golden and oracles unchanged |

### Wave 2d: M0 and what doesn't wait for it (started 18:20Z, main `98ea5276`)

| Id | Area | Owned paths | WPs and open items |
|---|---|---|---|
| W2.22 ✓ | The M0 scenario | `rig/scenarios/m0*`, `evidence/m0`, an `m0` oracle package, testkit adapters; fixes in finished rows' code | M0 as one composite L1 fixture on fortress, and L5 with two stock clients (one reconnects) against Shard and vanilla |
| W2.23 ✓ | Processors' unit and world statements | `logic/**`, `sim.logic`, a new `sim.ai.LogicAi`, unit sensors, additive `CallMapping`s | 69; `ubind`, `ucontrol`, `uradar`, `radar`, `ulocate`; `fetch`, `query`, `spawn`, `bullet`, `status`, `explosion`, `spawnwave`, weather; markers last |
| W2.24 ✓ | Keeping WASM open | `common/**`, `build-logic`, `tools/wasm-gate`, `scripts/` | fdlibm in `:common`, the registry as generated code, the JDK API swaps, a forbidden-API check in `check`, the nightly TeaVM gate |
| W2.25 ✓ | Review 40's fixes | `server.intent`, `sim.build`, `sim.blocks.turret`, `sim.fire`, `compat.v160.build`; additive in `sim.logic` | the rotation blocker; plan configs through `admitConfig`; block units in the hash; `deletePlans` indexed; the fire lookup; a committed AI toggle proof |

Review 40 covers wave 2c. Fog waits for W2.21, which holds
`server.sync`. The block-family fan-out (wave 3) waits for M0.

## Wave 3: the families M2 needs (started 19:45Z, main `d0fe3b88`)

M2 is build, mine, convey, power and defend on Serpulo maps. The shared brief had the gates for a block family
and the boat budget: at most 4 VM starts per row.

### Wave 3a

| Id | Area | Owned paths | WPs |
|---|---|---|---|
| W3.1 ✓ | Bridges and the mass driver | new kinds in `sim.blocks.distribution` | 33 |
| W3.2 ✓ | Crafting and heat | `sim.blocks.crafting`, new `sim.blocks.heat` | 34; W2.7's heater generator and flux reactor |
| W3.3 ✓ | Drills and pumps | `sim.blocks.production` | 35 |
| W3.4 ✓ | Liquids | `sim.blocks.liquid`; the liquid source and void | 36; posts the source, void and conduit early |
| W3.5 ✓ | Defence blocks and the sandbox rest | walls, doors, mines and thrusters in `sim.blocks.defense`; `sim.blocks.sandbox`; legacy and campaign | 39, 40 |
| W3.6 ✓ | Projectors and support turrets | projectors in `sim.blocks.defense`; new turret and repair files | 63, 64 |
| W3.7 ✓ | Unit items, mining and support AIs | `sim.unit` items and mining; `sim.ai`'s miner, builder and repair AIs; item intents | 45, 70 (first half) |
| W3.8 ✓ | Game flow, chat and menus | `server.chat`, new `server.game` and `server.ui`, `server.console`'s commands | 48, 49, 52 |

### Wave 3b (started 21:40Z, from staging `df4e4b5d`)

At most 3 VM starts per row.

| Id | Area | Owned paths | WPs |
|---|---|---|---|
| W3.9 ✓ | Payloads | `sim.blocks.payload`, the unit payload model, the payload intents and Calls | 65; posts `PayloadBlock` and the payload model early |
| W3.10 ✓ | Unit abilities | a new `sim.unit.ability` | 62 |
| W3.11 ✓ | The RTS control pathfinder | new files in `sim.path` | 71; posts its query API early |
| W3.12 ✓ | Objectives and markers | a new `sim.objectives`; the marker statements | 74; 68's markers and `localeprint` |
| W3.13 ✓ | Admin and the console | `server.admin`, `server.console`, the admin intents | 50; 51's command set |

Review 41 (GPT) reviews wave 3a's merged rows meanwhile.

### Wave 4a (started 2026-11-01, from main `9ae67530`)

At most 4 VM starts per row.
Opus 5.5 at medium; W4.8 and W4.9 on Sonnet 5.5.

| Id | Area | Owned paths | WPs |
|---|---|---|---|
| W4.1 ✓ | Unit factories and reconstructors | factory and reconstructor kinds in `sim.blocks.units`; `Units.canCreate` | 66 |
| W4.2 ✓ | CommandAI, commands, stances, RTS intents | new `sim.ai.command`, `CommandAi`, the RTS intents | 72 |
| W4.3 ✓ | Fog | `sim.fog`; per-team snapshot selection in `server.sync` | 73 |
| W4.4 ✓ | Advanced payload blocks | new kinds in `sim.blocks.payload` | 80 |
| W4.5 ✓ | Call order and the UDP path | `net/**`, `server.session`'s send path, `Outbox`'s threading | Q215, W2.21's net items |
| W4.6 ✓ | Tick allocation hot spots | the allocation sites, `tools/bench/` | 94 (first pass) |
| W4.7 ✓ | Cargo blocks, CargoAI, PrebuildAI | cargo kinds in `sim.blocks.units`, the two AIs | 70 (second half) |
| W4.8 ✓ | Wall-clock deadlines in the tests | test sources | none |
| W4.9 ✓ | A faster test loop: a shared Gradle build cache, `large` VMs, parallel breaks | `scripts/vm-gradle.sh`, `scripts/breaks/`, `scripts/gradle-cache/`, the `buildCache` block in `settings.gradle.kts`, the `testForks` default, `AGENTS.md` step 3 | none |

How it went:
- Merged in this order: W4.6 (allocation), W4.3 (fog, golden
  `0xc5c5f118553c790a` → `0xc6b64a5e94e08c4e`), W4.4 (payload blocks), W4.5 (Call order; staging check green in 14m58s),
  W4.2 (CommandAI; golden → `0x07772ddc050b4c25`, staging check green in 10m29s), W4.1 (factories), W4.9 (the test
  loop), W4.7 (cargo and prebuild) and W4.8 (counted deadlines).
- The golden moved twice, each time with a toggle proof: fog's `FogHashToggleTest`, then `CommandBatches`' (without
  it the hash is fog's).
- The rows' quirks are Q219–Q249; W4.5 fixed Q215. Two wall-clock and thread
  exceptions were added to `docs/architecture/tier-exceptions.md` (fog's 3-tick gate, formations computed at once).
- W4.8's counted checks and W4.9's cache changed how a row's gates run: a counted check needs `--no-build-cache`.
- Left for wave 4b: the unit assembler (WP81), team AIs (WP82), PvP (WP83), map patches (WP84), client RPC fuzzing (WP93).
