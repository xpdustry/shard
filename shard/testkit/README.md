# `:testkit`: scenarios and the differential runner (WP16)

TL;DR: describe a layout and a timeline once, run it on vanilla (through the oracle) and on Shard, and diff the two
worlds leaf by leaf, every tick if you like. A failure names the first diverging tick, every leaf that differs there,
both values and the value one tick earlier, and writes both worlds to `build/testkit/<scenario>/`. Test code only.

## Cheat sheet

```java
// build.gradle.kts: testImplementation(project(":testkit")) and shard { oracleTests() }; this goes in src/oracleTest/java
Scenario s = Scenario.named("conveyor-line").world(16, 5)    // or .map("fortress"), or ScenarioYaml.resource("/x.yaml")
    .place("item-source", 1, 2, 0, Config.item("copper"))     // block, x, y, rotation, config
    .row("conveyor", 2, 11, 2, 0)                              // x 2..11 at y 2, rotation 0 (east)
    .place("item-void", 12, 2)
    .step(600).build();                                        // actions go at the cursor; step() moves it
Differential.of(s)
    .vanilla()                                                 // the reference: OracleRunner.create()
    .candidate(ShardRunner.of(content))                        // your @Kind classes and @Register KindAdapters
    .tolerances(Tolerances.exact().abs("building[conveyor].fields.ys", 1e-5).ignore("building.fields.blendbits"))
    .trace()                                                   // every tick; stops at the first diverging one
    .run().assertNoDiff();                                     // failure: the leaves, both values, dumps
@Register public final class ConveyorAdapter implements KindAdapter<Conveyor>{  // only if your fields differ from vanilla's
    public KindKey kind(){ return KindKey.of("conveyor"); }
    public void fields(Conveyor c, Fields out){ out.put("len", c.len).put("ids", out.names().itemNames(c.ids)); } }
```

Without a second engine (a toy kind, a known number), check scripted values instead:
`Expect.of(s).at(60, w -> w.building(3, 2).field("len", 2).items("copper", 4)).check(runner).assertNoDiff()`.

## Scenarios

A `Scenario` is a map (`world(w, h)`, an empty stone world without cores, or a bundled `map(name)`), a gameplay
`seed` (Shard's world `Rand`, vanilla's `Mathf.rand`: the same xorshift128+), a tick count and timed actions.

- **Ticks:** tick 0 is the setup, before any tick runs. An action at tick `t` runs after `t` ticks, before tick `t + 1`.
  Actions of one tick run in list order. Projections at tick `t` include the actions of tick `t`.
- **Actions:** `place` (as the editor: no cost, no construction), `remove`, `configure`, `items` and `liquid` (straight
  into the building's module), `core-items`, `spawn` (world units, 8 per tile), `rules` (vanilla's field names) and
  `custom` (a named handler: `OracleRunner.on(name, …)`, `ShardRunner.on(name, …)`).
- **Builder:** `team("crux")` sets the team of the placements and spawns that follow; `row`/`column` place lines;
  `at(tick)` jumps the cursor forward.
- **Configs** are engine neutral (`Config.item`, `.liquid`, `.block`, `.unit`, `.of(int|boolean|String)`, `.point`,
  `.points`, `Config.NONE`). Points are passed as vanilla takes them: relative where the block reads them so.
- **`waitForPaths()`:** vanilla's ground units stand still until its pathfinder thread has built their flowfield, in
  wall time. This action waits for it (a no-op on Shard, which has no path threads). Put it after the spawns.

### YAML

`ScenarioYaml.read/write/load/resource`. One action per line, defaults left out, unknown keys are errors. The written
form is canonical, and `read(write(s))` equals `s`. An optional `tolerances:` section makes the file a self-contained
fixture for `Differential.of(ScenarioFile)`.

```yaml
schema: 1
name: toy-pumps
world: {width: 12, height: 8}       # or {map: fortress}
seed: 7
ticks: 400
actions:
- {at: 0, do: place, block: counter, x: 3, y: 5, config: {item: copper}}
- {at: 0, do: spawn, unit: flare, team: crux, x: 40.0, y: 44.0, rotation: 90.0}
- {at: 0, do: rules, set: {unitCap: 8, waves: true}}
- {at: 100, do: remove, x: 5, y: 2}
- {at: 100, do: open-valve, x: 5, y: 2}   # any other name is a custom action; its other keys are the args
tolerances:
  units: id                          # or type-order
  missing-fields: note               # or fail
  rules:
  - {abs: 'building[pump].liquids', by: 1.5}
  - {ignore: building.fields.blendbits}
```

Configs in YAML: `{item: copper}`, `{liquid: water}`, `{block: router}`, `{unit: dagger}`, `{int: 3}`, `{bool: true}`,
`{string: hi}`, `{point: [1, 0]}`, `{points: [[1, 0], [2, 0]]}`, or `null`. See `src/test/resources/scenarios/`.

## Runners

| Runner | Engine | Notes |
|---|---|---|
| `OracleRunner.create()` | vanilla v160.7 through the oracle's `Sim`, one server frame per tick | `global()`: one session per JVM at a time. Use it from `src/oracleTest` only. |
| `ShardRunner.of(content)` | a Shard `World` with the built-in kinds and systems | `.kinds(…)`, `.systems(…)`, `.adapter(…)`, `.world(…)`, `.on(…)`, `.maps(…)`, `.name(…)` |
| `Run.start(runner, scenario)` | drive one engine by hand | `stepTo(t)`, `project()`, `session()` |

**Shard adapters.** Family owners add these, from test sources (`@Register` puts them in the index), and edit nothing in
`testkit/`:

- `KindAdapter<B>`, one per kind:
  - `fields(b, out)` writes the kind's state under **vanilla's field names and meaning**. The default projects the kind
    class's public fields by their Java names.
  - `configure(b, config, names)` applies a scenario config as the kind's config handlers would.
  - `Fields`/`ShardNames` convert item ids, buildings (`"@x,y"`) and units (`"#id"`).
- `WorldAdapter` reaches what the foundation doesn't model: core items and waves (their owners: WP23, WP47). Without
  one, scenarios that set core items fail with a clear message, and those sections are left out of the projection.
  Rules are built in: `RulesAdapter` sets and projects the world's `WorldRules` by vanilla's field names.

Bundled maps load on Shard through `ShardMaps`, the real msav loader (`:compat`), when the runner's content is a pack
epoch: the map's survival rules, every building's base state, unported kinds passed through. `logic.play()` is not
done, so cores keep the file's items and health, and a map's saved units stay bytes; `ShardAgainstVanillaTest`
hosts every default map on both engines with exactly those tolerances. `.maps(loader)` replaces the loader.

**Vanilla fields.** A vanilla building's `fields` are the instance fields its build class declares below `Building`
(a conveyor's `ids`, `xs`, `ys`, `len`, …), when they hold something projectable. References to buildings and tiles
become `"@x,y"`, units `"#id"`, content its name, arc `Seq`s lists, and `Point2`/`Vec2` `[x, y]`. The rest is left out.
`OracleRunner.field(selector, name, reader)` adds a field by hand.

## The projection (schema 1)

`Projection` is the same JSON for both engines (`json()`, `Projection.fromJson`):

| Part | Content |
|---|---|
| `schema`, `engine`, `tick`, `width`, `height` | |
| `buildings[]` | `x`, `y` (center tile), `block`, `kind` (never compared), `team` (by name), `rotation`, `health`, `enabled`, `items` and `liquids` (non-zero amounts by name; null without the module), `power` (`{status}` or null), `fields` (kind state by vanilla's names). Sorted by (y, x). |
| `units[]` | `id`, `type`, `team`, `x`, `y` (world units), `rotation`, `health`. Sorted by id. |
| `cores` | team → item → amount, or null when the engine doesn't project cores |
| `rules` | every primitive, string or enum rule by vanilla's name, or null |
| `waves` | `wave`, `wavetime` (the countdown, in ticks), `enemies`, or null |

Bump `Projection.SCHEMA` when a part changes meaning.

## Diffs

`Differential` compares at checkpoints: `atEnd()` (the default), `every(n)`, or `trace()` (every tick). It stops at
the first failing checkpoint. The two sides run in lockstep. Two vanilla runners can't coexist, so for vanilla against
vanilla the reference runs first, its checkpoints are stored as leaf deltas, and the candidate is compared against the
replay. The reference side is the one tolerances and notes treat as the truth.

**What is compared.** Each projection is flattened into entities (`building(3,4)`, `unit#12`, `core(sharded)`,
`rules`, `wave`, `world`) of scalar leaves (`health`, `items.copper`, `fields.ys[2]`).

- Missing item, liquid and core amounts count as zero.
- A building whose block differs is one difference, not one per field.
- A kind field only the reference has (one the candidate hasn't ported) is listed under "not compared". With
  `missingFields(FAIL)` it fails instead.
- A kind field only the candidate has fails: it is a typo or a stray.
- A section an engine doesn't project (Shard's cores and waves, for now) is listed under "not compared".

**Tolerances.** Patterns are prefixes over the flat paths. The last matching rule wins.

```
building.health   building[conveyor].fields.ys   building[kind=item-turret].fields   building(3,4)
unit[dagger].x    unit#12   core[sharded].copper   rules.waveSpacing   wave.wavetime   *.health   building.fields.*.x
```

Rules: `ignore`, `exactly` (takes a leaf back out of a broader rule), `abs(pattern, ε)`, `rel(pattern, fraction)`.
Units pair `units(UnitMatch.ID)` (the default: Shard replays vanilla's id counter, buildings included) or
`TYPE_ORDER` (the n-th dagger of a team on each side). `unitPaths("dagger", …)` is the named tolerance for the
documented vanilla nondeterminism below.

**Reports.** `DiffReport.text()` looks like this (from `TraceTest`, a pump broken on purpose):

```
FAIL scenario 'toy-pumps': shard (reference) vs shard (broken) (candidate), trace, 400 ticks
  first divergence at tick 100 (tick 99 agreed)
    where                             shard shard (broken) tolerance at tick 99
    building(2,2).liquids.water  pump 25.0  26.0           exact     24.75
  not compared: core, wave (not projected by shard, shard (broken))
  dumps: …/build/testkit/toy-pumps
  time: shard 0.00 s, shard (broken) 0.00 s
```

The dumps directory holds:

- `tick-<t>.<side>.txt` for the failing checkpoint and the one before, one `entity.path = value` per line;
  `diff -u tick-99.reference.txt tick-100.reference.txt` shows what one side did in that tick;
- `tick-<t>.<side>.json`, the structured projection (where it was kept);
- `report.txt`.

`assertDivergesAt(t)` is for tests that break something on purpose.

## Vanilla, as the oracle runs it

Two vanilla runs of one scenario agree on every leaf at every tick (`VanillaAgainstVanillaTest`, tested on an
item, crafting, liquid, power and flying-unit layout, and on a hosted `fortress`). The exceptions and traps found
while building this:

- **Ground units and the pathfinder threads** (the documented exception). Ground AI walks flowfields that
  `Pathfinder` threads build on their own clock. Until a field is ready the units stand still, so when they start
  walking depends on wall time. `waitForPaths()` after the spawns made a 300-tick dagger march exact in testing
  (`GroundUnitsTest`). Fields rebuilt later (after tile changes) are async again: use `unitPaths(...)`.
- **Coreless teams have a unit cap of 0**, and vanilla kills a unit over the cap on `add` (`UnitComp.java:605`). On an
  empty world, set `rules: {unitCap: …}` or place a core, or the spawns die at once.
- **Crux only fights on an empty world with waves on.** A team only sees enemy cores when it is active
  (`Teams.updateEnemies`): it has a core, or it is the wave team with `waves` on. It only gets ground AI instead of
  an idle `CommandAI` when it is an AI team (`Team.isAI`). `{waves: true, waveTimer: false}` does both.
- `OracleRunner` rewinds `Time.time` to 0 on open (vanilla's `Logic.reset` keeps it, which shifts timers between two
  runs in one JVM), and boots vanilla before `Vanilla.emptyWorld` (which reads `Blocks.stone` before booting).

## Cost (measured on a `default` boat VM, 4 vCPU)

| Run | Time |
|---|---|
| Shard, 10k-tick trace, 90 toy buildings and 10 units, lockstep | 1.0 s |
| the same, recorded and replayed | 1.6 s |
| vanilla against vanilla, 10k-tick trace of the factory layout (about 40 buildings, 2 units), both runs | 3.5 s |
| vanilla against vanilla, `fortress` idle for 1200 ticks, trace | about 3 s |

## Layout

| Class | What |
|---|---|
| `Scenario`, `Action`, `Config`, `MapSpec` | the scenario model and its builder |
| `ScenarioYaml`, `ScenarioFile` | the YAML form |
| `Runner`, `Session`, `Run` | the engine SPI and the timeline driver |
| `OracleRunner` | vanilla |
| `ShardRunner`, `KindAdapter`, `WorldAdapter`, `RulesAdapter`, `Fields`, `ShardNames`, `Teams` | Shard (names and ids from the world's epoch) |
| `ShardMaps`, `BundledMaps` | bundled maps on Shard, and the vanilla jar's map files |
| `Projection`, `Values`, `Flat` | the normalized view, value rules, the flattened form |
| `Differential`, `Tolerances`, `UnitMatch`, `DiffReport`, `Difference`, `Expect` | diffs and expectations |
| `Compare`, `Trace` | internals: the comparison, the recorded reference |
