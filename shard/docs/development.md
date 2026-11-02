# Developing Shard

The developer's guide; the overview and quick start are in the README. The binding design is
[`ARCHITECTURE.md`](../ARCHITECTURE.md); how agents work on it is [`AGENTS.md`](../AGENTS.md). This file covers the
build: modules, building and testing, deploying, registration, the world model in code, and the determinism rules.

## Modules

The module list (packages, dependency edges, what each owns) and its rules are `ARCHITECTURE.md` §2.
`checkModuleDependencies` (part of `check`) fails the build on any edge that table does not list.

Every package has a `package-info.java` that says what it holds and which work package owns it.

Rules the check enforces (`build-logic/.../ModuleRules.kt`):
- A module may depend on what its row lists, transitively; tests may also use `:testkit`, `:tools:oracle` and
  `:tools:indexer`.

**Adding a module:** an `include(...)` line in `settings.gradle.kts`, a row in `ModuleRules.allowed`, a
`build.gradle.kts` that applies `id("shard.java")`, and a `package-info.java`.

## Building and testing

Conventions live in the included build `build-logic/`, as the `shard.java` plugin: a Java 27 toolchain with
`--release 25`, `-Xlint:all` (minus `serial`, `processing`) and `-parameters`, JUnit
6, the registration processor, the dependency check.

**The JDK.** Gradle runs on the toolchain's JDK, Eclipse Temurin 27 (Oracle's builds have no Shenandoah). Locally,
`mise.toml` selects it (`mise install java@temurin-27`); on boat VMs, `scripts/vm-gradle.sh` installs a pinned,
checksummed build as `/opt/jdk27` on any VM that lacks it. `bin/shard` refuses an older JDK.

Everything runs locally with `./gradlew` (`./gradlew check` is the full suite: about 8 minutes on 8 vCPUs, and
it wants the RAM). The scripts below run it on a boat VM instead, so a shared or small machine keeps its memory:

```sh
./gradlew :sim:compileJava                      # local, quick
./gradlew check                                 # everything, locally
scripts/vm-gradle.sh check                      # everything, on this checkout's VM
scripts/vm-gradle.sh :sim:test --tests '*Fork*' # one module, some tests
scripts/vm-gradle.sh --pull :common:test        # then copy build reports to build/vm-reports/
scripts/vm-gradle.sh --delete                   # when you finish (local daemons stop after 5 idle minutes)
```

The VM keeps its Gradle daemon warm between runs; `SHARD_TEST_FORKS` (on the VM, 3 per 8 vCPUs; 1 locally) sets test forks. With `~/.cache/shard/gradle-cache/credentials`
present, the VM reads and pushes a shared build cache (`scripts/gradle-cache/`); `SHARD_CACHE=off` turns it off, and a check that must count
real test runs adds `--no-build-cache`.

**Test kinds:**
- `src/test/java`: plain JUnit tests in one JVM. `shard { vanillaJarInTests() }` puts the vanilla server jar on
  the test classpath, for comparisons with Arc's and vanilla's pure classes (`arc.math.Mathf`, `EntityGroup`).
  These tests must not touch vanilla's static game state.
- `src/oracleTest/java`: tests that boot vanilla through `:tools:oracle`. Enable with `shard { oracleTests() }`.
  The `oracleTest` task runs one JVM per test class (`forkEvery = 1`, 768 MB, `--enable-native-access`) and is part
  of `check`.
- Tags `slow` and `client` are skipped unless asked for: `-Pshard.tags=slow`, `-Pshard.tags=slow,client` or
  `-Pshard.tags=all`.

**The vanilla jar.** The oracles, and a few tests, run vanilla v160.7's `server-release.jar`. Gradle takes
`-PvanillaJar=<path>`, else `~/.cache/shard/vanilla/v160.7/server-release.jar`, else downloads it from Mindustry's
GitHub release and checks its SHA-256 (`Vanilla` in `build-logic/.../ShardExtension.kt`). The scripts outside Gradle
read `~/.cache/shard/vanilla/v160.7/`, which `scripts/fetch-vanilla.sh` fills (`--client` adds the desktop jar).

### Nightly L4

`scripts/nightly-l4.sh` is the long run (ARCHITECTURE.md §6, L4): `check --continue -Pshard.tags=slow` on the
checkout's boat VM, so every suite runs, slow ones included:
- the wire fuzzers: `ClientDecoderFuzzTest` (client to server) and `ServerPacketFuzzTest` (server to client);
- `:logic`'s `FuzzOracleTest`;
- `:sim`'s `PathTimingsTest` and `CheckpointBench`.

The fuzzers mutate vanilla-written packets and compare Shard's verdict with vanilla's. Each decoder runs at least
`-Pshard.fuzzCases` cases (default 4000), then until its share of `-Pshard.fuzzMinutes` is spent. The minutes are per
direction; `--minutes N` (default 10) sets them.

The script writes its log to `build/nightly-l4/`, pulls the reports to `build/vm-reports/` and deletes the VM unless
you pass `--keep`. It exits with Gradle's code. Nothing schedules it: run it from a timer or CI on a clean checkout.

```sh
scripts/nightly-l4.sh                    # about 20 minutes of fuzzing, plus check
scripts/nightly-l4.sh --minutes 60 --keep
```

## Deploying

`./gradlew :server:installDist` builds the server in `server/build/install/shard/`. Copy that directory to the host and
run `bin/shard --config <dir>` on Eclipse Temurin 27, always from the same working directory (relative paths in the
config, and `SHARD_DATA`, resolve against it). `bin/shard` reads:
- `JAVA_OPTS` or `SHARD_OPTS`, the JVM's options: set `-Xms` to `-Xmx`, since the heap is pre-touched;
- `SHARD_GC`: `shenandoah` (generational, the default where the JDK has it) or `g1` (small hosts);
- `SHARD_DATA` (default `data`): JFR recordings, temporary files and the AOT cache;
- `SHARD_AOT`: `auto` (the default), `train` or `off` (below).

**Train the AOT cache once per deploy.** `bin/shard` starts from an AOT cache of the classes the server loads and
links (JEP 483), if one was trained for this build, JDK and install. With one, 18 worlds were ready about 0.5 s sooner,
and the first player's join took a 3-5 ms tick instead of 9-12 ms. The launcher never trains one on its own: the JVM
records no JFR while it trains. After installing a build or a JDK, train it on purpose, with the server's own
environment, working directory and `--config`:

```sh
bin/shard --aot-train --config <dir>
```

The training run hosts the config's worlds on `127.0.0.1:6599` (`SHARD_AOT_TRAIN_PORT`), with a throwaway database,
checkpoints and log file and no ops API, so it can run beside the live server. 40 bots from the load generator the
install carries in `tools/bots/` (`SHARD_BOTS` names another) join, chat and move between the worlds for 20 s, one is
kicked, and the JVM writes the cache as it exits, under `$SHARD_DATA/aot/` (old builds' caches go). The next start uses it; nothing else changes. A start without one says so on stderr, and `SHARD_AOT=off` runs
without the cache.

## Registration

Adding a phase handler, a world part or a building kind edits no shared file. Annotate the SPI type with
`@Registry` and each implementation with `@Register`:

```java
@Registry public interface PhaseHandler{ ... }

@Register public final class BuildingUpdate implements PhaseHandler{ ... }
```

An annotation that is itself annotated `@Register` registers too, and carries a `String value()` the index
records. `:sim`'s `@Kind` works this way:

```java
@Kind("conveyor") public final class Conveyor extends Building{ ... }
```

At compile time `:tools:indexer` checks each registered class (public, concrete, static if nested, a public
no-argument constructor, at least one `@Registry` supertype) and generates the source set's index as Java: a
`RegistryIndex` (`com.xpdustry.shard.sim.SimRegistryIndex`, `SimTestRegistryIndex`...; `shard.java` names it) that
declares each registry and each class with a constructor reference (`Conveyor::new`) and its registration
annotation's `value()`, listed in `META-INF/services`. At run time `Registries` loads every source set's index with
`ServiceLoader` and merges them: `Registries.classes(PhaseHandler.class)` sorted by class name, so class path order
never matters, `Registries.instances(...)`, `Registries.constructor(type)` and `Registries.key(type, Kind.class)`.
Nothing reflects, enumerates resources or loads classes by name. The
processor is aggregating for Gradle's incremental compilation.

A class that isn't registered (a test's fixture part or kind, which `@Register` would add to every world on the class
path) comes with its constructor: `Systems.of(handlers, parts).with(Probe.class, Probe::new)`,
`kinds.withKind(key, RecordingWall.class, RecordingWall::new)`.

Records that implement a `@Shaped` type (timer tasks, `Timers.Task`) get a shape in the index: their component
accessors and canonical constructor, generated next to them, which `Registries.shape(type)` returns. That is how
timer tasks are copied for forks and hashed without reflection; a task must be a record.

## The world model in code

ARCHITECTURE.md §3 is the design. Here is where it lives:

- `sim.world.World`: the root. `tick()` advances Δ = 1 through the fixed `Phase`s; `fork()` deep-copies;
  `hash()` returns a `WorldHash` with one section per subsystem.
- Inputs: any thread may `world.submit(command, cause)` or offer an `IntentEnvelope` to `world.inbox()`, which is
  bounded. In `COMMANDS` the world admits them in arrival order (membership, route, sequence, then the input's own
  checks) and appends each with its `Verdict` to the `CommandLog`.
- Objects: `Building` (one subclass per kind, `@Kind`), `Unit` (one class, composed parts, a `UnitController`), on
  the `sim.entity.Entity` base with `isAdded()` and attachments. The update lists are `ActiveList`s with vanilla
  `EntityGroup` order (swap-remove, cursor step-back, same-pass appends).
- Tables: `TileGrid` (flat columns; `build` indexes the `Buildings` registry), `Bullets` (struct of arrays).
- Extension state: a `@Register` class implementing `WorldPart`, reached with `world.part(MyPart.class)`.

**Writing a kind.** A kind copies and hashes its own fields; the fork tests compare copies field by field:

```java
@Kind("router")
public final class Router extends Building{
    public Building lastInput;
    public float time;

    @Override
    public void updateTile(){ ... }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (Router)into;
        out.lastInput = context.map(lastInput); // references to world objects go through the context
        out.time = time;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.i(lastInput == null ? -1 : lastInput.id()).f(time);
    }
}
```

`CopyContext.map` copies through a work list, never recursively, so cycles and long chains are fine. A reference to
a removed object is copied as a detached object, so the fork sees the same `isAdded()`/`isValid()` answers.

**Checkpoints.** Each piece of state has a `Persistence`: `AUTHORITATIVE` (stored and hashed, including list
order, `Rand` state and attachments), `DERIVED` (rebuilt on restore; it must rebuild to exactly its state at a
tick boundary), or `EXTERNAL` (the inbox and observers: not world state).

## Determinism rules

A world's next state depends only on its current state and its logged inputs.

- State comes only from the `World`; randomness only from `world.rand()` (Arc's xorshift128+).
- Math only from `:common` (`Mathf`, `Angles`…), which matches Arc bit for bit, and `StrictMath` for
  transcendentals. Never `java.lang.Math.sin` and friends: HotSpot's intrinsics for them differ by CPU and JDK.
  `checkTickMath`, part of `check` for `:common`, `:content`, `:logic` and `:sim`, fails on them (report in
  `build/reports/tick-math.txt`). Oracle JVMs run vanilla without those intrinsics, so its `Math` is `StrictMath`;
  `LibmTest` (`:tools:oracle`) fails if a new JDK adds one the flag doesn't cover.
- Ordered collections only: no `HashMap`/`HashSet`, no `Set.of`/`Map.of` (their iteration order changes between
  JVM runs). `TreeMap`, lists, arrays and the `:common` structs are fine.
- No threads, executors or sockets in `:sim`. Other threads talk to a world through its inbox; observers read
  immutable events through bounded queues.
- Time, the fixed tick and iteration order: `ARCHITECTURE.md` §4 ("Fixed tick") and §3 ("Iteration order is gameplay").

`DeterminismTest` scans `:sim`'s compiled classes for these and fails the build. `ForkTest` and `HashTest` check
that a fork continues bit for bit and that the golden hash of a scripted world doesn't move by accident. `Hasher`
hashes every NaN as the canonical one: engines choose NaN payloads differently, and Java's comparisons can't see them.
