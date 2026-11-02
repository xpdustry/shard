# Shard architecture (round 3, first full run)

Binding decisions for every implementation agent. Each line comes from a numbered design note (not in this repository), and
review 35 corrected this version. Read the note behind your area before you build it. If you must break a decision,
say so in your final report with the reason. Never break one silently.

This file is the binding core: the exactness tiers, module boundaries and invariants every agent needs. Per-subsystem
detail is one hop away in `docs/architecture/`, and each section below says when to open it.

## 1. What we are building

A from-scratch Mindustry **v160.7** server (Mindustry `17012d61`, Arc `7445105c`). It runs on JDK 27 (Eclipse Temurin);
its class files target Java 25 (`--release 25`). It runs many worlds per JVM, and an unmodified desktop client can
join and play real maps. No vanilla code is on the runtime classpath; vanilla is only a test oracle
(`:tools:oracle`).

**Exactness tiers.** Know your tier before you write code.

| Tier | Requirement | Applies to |
|---|---|---|
| A | Byte-exact. The same discrete Calls in the same order relative to snapshots. | packets, world stream, every building's save bytes, entity `writeSync`, synced-block `writeAll`, rules JSON, content ids |
| B | Exact server-side semantics against the vanilla oracle at Δ = 1, where that is reproducible. A tolerance per family on what players can observe. Real clients must converge. | conveyors, bridges, power, processors, every non-synced block |
| C | Bounded drift, corrected by snapshots | units, core items, health, synced blocks |
| D | Free. Judged by gameplay and performance. | AI, pathfinding, wave spawn choice, fog decisions |
| E | Not simulated on the server | display drawing, effects, sounds, turret aim visuals |

Which parity clients need and which only the oracles need, for a cleanup: note 42.

**Tier A and Tier B exceptions.** Vanilla isn't reproducible with itself in six places. Open
`docs/architecture/tier-exceptions.md` before you pin an oracle fixture to vanilla, write a Tier A or B gate, or port
anything timed by a clock or a thread. Tier A narrows for: JSON inside the world stream and Calls, the world stream as
a whole, and the processor `sync` cadence. Tier B narrows for: **Proximity order**,
**Process-wide history, kept per world**, and **Wall-clock and thread timing**.

The client simulates **everything** itself (note 26 §2), and nothing corrects Tier B state after the join. So port
block semantics exactly and redesign only the data layout around them. We don't claim that live client state stays
tick-identical: clients run at their own frame rates.

## 2. Modules and packages

Package root: `com.xpdustry.shard`. The arrows are compile dependencies. The graph is acyclic and enforced by a
Gradle check.

| Module | Package | Depends on | Owns |
|---|---|---|---|
| `:common` | `.common` | — | Arc-exact math (`Mathf`, `Angles`, `Geometry`, `Rand` xorshift128+), `Point2` packing, primitive collections, `Writes`/`Reads` byte IO. No game concepts. |
| `:content` | `.content` | common | Immutable typed definitions, wire ids, kind keys and kind-local parameter arrays: YAML → frozen `ContentEpoch`. The generated vanilla pack (resources). No dependency on `:sim`. |
| `:wire` | `.wire` | common | Wire DTOs and codecs generated from `wire/schema/v160.7.yaml`, `TypeIO`, packet registry, frame structs, narrow read-only lookup interfaces. Never imports `:sim`. No sockets. |
| `:logic` | `.logic` | common, content | mlog assembler and interpreter; the `LogicHost` SPI that `:sim` implements |
| `:sim` | `.sim` | common, content, logic | `World`, its objects and tables, phases, the mutable `Rules` model, the kind registry (content kind key → `Building` subclass), all gameplay, Shard's native checkpoints. No threads, sockets, clocks or wire bytes. |
| `:compat` | `.compat.v160` | common, content, wire, sim | Every v160.7 byte format: building body codecs per kind and revision, entity sync adapters, the world-stream writer, msav import/export, Rules/objectives/markers JSON, content patch output. It reads immutable world projections. |
| `:net` | `.net` | common | Arc-net TCP+UDP transport on virtual threads, framework messages, LZ4, stream chunking |
| `:server` | `.server` | everything above | `main`, config, the world scheduler and lifecycle, sessions and transfers, intent decoding, the sync and fog gate, admin, chat, console, storage, ops |
| `:testkit` | `.testkit` | sim, compat, server, tools:oracle | scenario DSL, differential runner (Shard vs oracle), shared fixtures |
| `:tools:oracle` | `.oracle` | vanilla jar | exists: headless vanilla, decoders, golden bytes, `Sim`, dumps |
| `:tools:wirelift` | `.tools.wirelift` | vanilla jar | lifts the wire schema from the jar's bytecode; `WireGen` (proto 25) |
| `:tools:content-extract` | `.tools.extract` | vanilla jar | vanilla → YAML pack (proto 20 `Extract.java`) |
| `:tools:bots` | `.tools.bots` | net, wire | headless protocol bots and the load generator (note 24 §6) |
| `:tools:indexer` | `.tools.indexer` | — | the annotation processor behind `@Registry`/`@Register` (`@Kind`, handlers, commands) |
| `rig/` | scripts | — | vanilla client on a boat VM: join, scripted input, screenshots, log verdicts (L5) |

Rules:
- Adding a module means adding it to `ModuleRules.allowed` in `build-logic` as well; the check fails otherwise.
- Tools are never on a runtime classpath (`:tools:indexer` is an annotation processor, so it is compile-only).
  One packaging exception: `:server`'s install carries `:tools:bots`' jars in `tools/bots/`, off its classpath, for
  `bin/shard --aot-train` (`ModuleRules.packagingOnly`).
- `:sim` never imports `:wire`, `:compat`, `:net` or `:server`.
- `:compat` reads world state only through projections that are frozen at a tick boundary, never live columns from
  another thread.
- A block family spans two packages: `sim.blocks.<family>` (behaviour and state) and `compat.v160.blocks.<family>`
  (its save and sync codecs). One owner holds both.
- Nothing in `:sim` reads the wall clock, starts threads or iterates a `HashMap`/`HashSet` (§4).

## 3. The world model (objects for behaviour, tables where they pay)

The decision after note 21: buildings and units are **objects**, because they are the code we port (about
30k lines of Tier B behaviour) and the code people customize. Note 21 measured no speed winner for them. **Tables**
(primitive columns) are kept where they measurably pay: bullets, the tile grid, spatial grids and flowfields.
Generated table-backed accessors for buildings and units are a later experiment, considered only once the runtime
and its scalability are proven.

**Ownership.** One `World` owns everything it simulates, and exactly one thread at a time writes to it. Everything
it owns hangs off one root, with no pointers leaving the world. That is what makes `World.fork()` and checkpoints
possible (note 27).

Open `docs/architecture/world-model.md` before you write a `Building`, a `Unit`, an entity reference, a table or copy
code: **Objects** (`Building`, `Unit`, shared entity machinery, references, **Attachments**), **Tables** (`TileGrid`,
`Bullets` and the entity ids bullets use up, derived indexes), **Fork and checkpoint** (`copy(CopyContext)`).

**Iteration order is gameplay:**
- Each vanilla group (`Groups.build`, `unit`, `bullet`, …) has its own ordered active list. Its insertion, removal,
  sleep and wake rules are ported and tested, and its order is persisted in checkpoints.
- Loop with a cursor; when swap-removing, step the cursor back.
- Spawns in the same tick append, frags included (deferring them changed results by 3%, note 21 §10).
- Never sort by id where vanilla doesn't.
- The building update calls `updateTile()` on each active building in that order.

### Tick phases (vanilla `Logic.update`, plus Shard's)

```
COMMANDS     admit queued intents and commands in arrival order; log each one with its verdict (§4)
PATH_FLUSH   tile changes → incremental flowfield repair (note 22); then ControlFlush, the RTS pathfinder's pass
TEAMS        team stats; visibility pass when fog is on (note 23); fillItems / buildAi / rtsAi / prebuildAi
TIME         timers (vanilla Time.run)
LOGIC_VARS   @time / @tick globals
WEATHER      server-side weather
OBJECTIVES   map objectives
WAVES        countdown and runWave
ENV          weather attributes
PHYSICS      bullet and unit physics; the owner documents the exact order (index rebuild, one-tick-late push,
             what mid-tick spawns see) and checks it against vanilla traces (note 29)
PLAYERS, EFFECTS, MISC   players; fires, puddles, labels…
UNITS        brains (low rate, staggered) → steering → movement → weapons; builders revalidate and commit plans here
POWER        graphs (note 30)
BUILDINGS    the active list in vanilla order; **processors execute in their own building's turn**, with instruction
             accounting around each call (note 28's engine, vanilla's placement in the tick)
BULLETS      update, then collide
GAME_STATE   checkGameState
SYNC         freeze the projections the sync layer encodes, at snapshot cadence (§8)
```

The phases are a fixed enum. Built-in subsystems register handlers through the registration index. In the first run,
plugins don't register phase handlers (§4).

## 4. Determinism, commands and history (notes 27, 32, 33, review 35)

**Fixed tick.** Every gameplay tick advances exactly Δ = 1.
- A late world runs at most 3 catch-up ticks per scheduler turn, then yields, reports lag and applies the overload
  policy (admission limits, pause or quarantine). It never changes Δ. A variable-Δ mode would be a separately named
  compatibility mode with its own gates; it doesn't exist yet.
- The sim never reads a clock. Durations measured outside it, such as packet arrival times used by movement
  validation, are recorded inputs.
- Each world has one seeded gameplay `Rand`. Use `:common`'s Arc-exact math only. Work done off the tick thread is
  joined at fixed ticks.

Open `docs/architecture/commands-and-plugins.md` before you add an input path, an admin action or a plugin hook:
**Intents → commands** (the five steps from packet to committed plan), **Plugins and observers**,
**Logic throttling**, **Journal**.

Admin actions, AI, scripts and async results also enter as sealed `Command` records through the same log. No API
mutates a world from another thread.

**Observation is pure.** These never change world state, hashed bookkeeping included:
- a world-stream capture, a save, a checkpoint or a fork;
- a block snapshot, an entity snapshot or a state snapshot;
- a sensor read, or a plugin's read.

A world that was observed must tick exactly as an identical world that wasn't, or a join would make it diverge from
its replay. RP2 found `Processor.save()` unparking a waiting processor (`ObservationTest`). Where vanilla's
writer has a side effect, such as `materialize`, apply it to a copy or restore it after the write.

**`WorldHash`:** a rolling hash per subsystem, checked every tick in tests.

**Checkpoint contract.** The world owns a versioned checkpoint covering:
- authoritative columns and object state;
- active-list orders, `Rand` state and timers;
- rules, pending actions and plans;
- typed attachments;
- the content hash and the build/schema ids.

Derived indexes (spatial grids, flowfield arrays, chunk buckets) are classified as rebuildable and are rebuilt on
restore. State counts as derived only if rebuilding it at a tick boundary gives exactly that state. Anything that is
stale on purpose between ticks is authoritative and goes in the checkpoint. Example: the path service checkpoints its
field registry, reference counts, RTS build progress, targets and its view of the world at the last flush (which
lags the live world by design), then rebuilds the field arrays from them.
Capture is a bulk copy at a tick boundary; compression runs off-thread (note 34).

**Crash bundle:** on a world exception, write the last checkpoint, the command log and the hashes to a zip. It ships
with a same-build `shard replay <bundle>` reader, the first user of the log. General replay is a stretch goal.

## 5. Content (note 20)

Open `docs/architecture/content.md` before you touch content files or kinds: **Format** (YAML keys, inheritance, field
tags), **The vanilla pack** (generation, and baking the derived `init()` values), **Reload** (the four steps and what
is rejected), **Per-world overlays → patchgen**, **Kinds** (registration, **Passthrough**).

**`ContentEpoch`.**
- A frozen snapshot with dense arrays indexed by **wire id**: Shard ids are the client's ids.
- Shared by all worlds with the same content hash.
- Old epochs are retained by hash while checkpoints, joins or live objects still reference them.

## 6. Wire (note 25)

Open `docs/architecture/wire.md` before you add or change a packet, codec or string limit: **Schema and codecs**
(lifted schema, generated codecs, `checkWireGen`), **Hand-written in `:compat`**, **String limits**.

**Gates (note 25 §3).** A local compile is not a gate.

| Gate | Checks | When |
|---|---|---|
| L1 | codec ⇄ vanilla bytes | every boat build |
| L2 | golden fixtures | every boat build |
| L3 | vanilla loads Shard's world stream into the same state | every boat build |
| L4 | differential fuzz of client → server decoders | nightly |
| L5 | real client on a boat VM | per milestone |

**Versions.** Only the v160.7 pack ships. A pack-independent bootstrap decoder reads the first connect packet, whose
layout is the same in v146, v159 and v160.7 (tested). Validate any further build's bootstrap before adding it.

## 7. Network, threads, sessions, lifecycle (notes 24, 33, 34)

**No Netty.** Virtual threads plus blocking IO.

Open `docs/architecture/runtime.md` before you touch threads, the transport, sessions, a world transfer, the join
protocol, the scheduler or JVM flags: **Threads** (table), **Transport**, **Sessions**, **Join protocol**,
**Scheduler**, **JVM** (collector choice, AOT cache).

**World lifecycle:** `RUNNING → QUIESCING → HIBERNATED → WAKING → RUNNING`, plus `FAILED`.
- An empty world keeps a 1 Hz maintenance loop with **simulation time frozen**, unless configured for continuous
  simulation, in which case it stays at 60 Hz. One 1 Hz tick never stands for 60 gameplay ticks.
- After 10 empty minutes it hibernates. This happens only after a verified, fsynced checkpoint generation and an
  atomic `CURRENT` publication. On failure it keeps the last good generation and stays resident.
- Wake is single-flight. The world reports its durable tick and the checkpoint's age.

**Binding:** only `127.0.0.1` on the dev box. A VM may bind anything.

## 8. Sync and fog (notes 23, 25, 26)

**The `Outbox` gate.**
- Every outbound path goes through it: join, Calls, snapshots and plugins.
- It has four classes: global, team, visible-at-point and filtered snapshot.
- An encoding is shared only between viewers for whom every serialized field and every visibility rule is the
  same. Private entities and view-dependent output get per-viewer projections.

**Cadence.** Vanilla's comes first:
- entities and state every 200 ms;
- block health every 0.5 s;
- synced blocks every 6 s.

Sending snapshots for non-synced blocks to repair Tier-B drift is an experiment that needs per-kind verification and
bandwidth numbers. What a stock client does with them, and the plan: note 42.

**Non-fog mode.** Vanilla-compatible movement correction at 14 tiles.

Open `docs/architecture/fog.md` before you implement, enable or test real fog: the conditions it needs, what the world
stream holds under it, and the probing tests.

**Rate limits:** token buckets per packet type, in every mode.

## 9. Subsystems

Open `docs/architecture/subsystems.md` before you build or change one of these areas, and read only its block (the
number is the research note):
- **Pathfinding (22):** `PathService`, flowfields and their fresh-field checks, the RTS `ControlPathfinder`, unit steering.
- **Physics (29):** `SpatialIndex`, `BodyTree`, hitbox and bullet-walk rules.
- **Logic (28):** the mlog interpreter, processor links, `LogicBuild` saves.
- **Logistics and power (30):** conveyors, power graphs, liquids.
- **Combat (31):** bullets, weapons and abilities as data; unsupported content.
- **Construction and economy (32):** the intent executor, treasury, plan limits.
- **Persistence and ops (34):** msav versions, config, SQLite, autosave, admin API.

## 10. Milestones

- **M0, the vertical slice.** It must pass before the block-family fan-out:
  - two stock clients join a real bundled map, and one reconnects;
  - a player builds a conveyor line that a processor controls;
  - a power consumer runs;
  - a turret shoots a wave unit;
  - L1–L3 are green and L5 runs on boat.
- **M1:** a vanilla client joins all 18 default maps, walks and sees cores, with no desync log lines.
- **M2:** build, mine, convey, power and defend work on Serpulo maps.
- **M3:** every family is ported; Serpulo and Erekir soak tests run with bots plus real clients; several worlds
  run at once.

## 11. How agents work

See `shard/AGENTS.md`: ownership, building on boat, oracle usage, commits and the final report format.
