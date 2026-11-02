# Working on Shard

Shard is a from-scratch Mindustry v160.7 server. The binding design is `ARCHITECTURE.md`. The numbered notes it cites
("note 37") are the design research behind it, which is not part of this repository. Bind servers to loopback only,
and start boat VMs with `--no-env`.

## Where to look

Open the file that matches what you are about to do, not all of them. Paths are relative to the repository root.

| Open | When |
|---|---|
| `ARCHITECTURE.md` | Before any code: §1 (exactness tiers) and §2 (module boundaries), then every § your brief names. Each § points to `docs/architecture/*.md` for its subsystem detail; open one only when its pointer matches your area. |
| `WAVES.md` | You need to know who owns a path, which row (`W2.21`, `J27`, `RP2`...) a contract or an open item belongs to, or what a neighbouring area already ships. It is an index: each row's text is in the per-area file it names. |
| `tools/oracle/README.md` | You write or read an oracle test: the API cheat sheet for booting vanilla, decoding bytes and stepping ticks. |

## Steps

1. **Read** your brief and the `ARCHITECTURE.md` sections it names.
2. **Stay in your owned paths.** Your brief lists them. Outside them, only make append-only edits to the shared
   files (module includes in `settings.gradle.kts`, entries in `gradle/libs.versions.toml`, registration lines).
   Anything else you need from another area goes in your report under "Requests".
3. **Build and test on boat.** Quick local compiles are fine (`./gradlew :sim:compileJava`). Run every test suite
   through `scripts/vm-gradle.sh` (for example `scripts/vm-gradle.sh :sim:test`). It syncs this checkout to your
   own VM (a `large` one: 8 vCPU, 16 GB) and keeps the daemon warm. Start any run longer than a minute in the
   background and wait on it: the lead's messages reach you only between tool calls. Run
   `scripts/vm-gradle.sh --delete` before you report. Local daemons stop themselves after 5 idle minutes.
   `./gradlew --stop` would stop every other row's daemon too.
   - **Shared build cache.** The script gives the VM's Gradle a remote build cache served from the dev box
     (`scripts/gradle-cache/`), read and push. A fresh VM then gets `FROM-CACHE` for every task another row's VM already
     ran on the same inputs, compiles included. `SHARD_CACHE=off` skips it. A counted full check must run the tests, not
     restore them: `cleanTest cleanOracleTest check --continue --no-build-cache`.
   - **Test forks** come from the VM's CPUs and memory; `SHARD_TEST_FORKS=N` overrides them.
   - **Inner loop: `scripts/vm-gradle.sh --affected`.** It runs `check` with only the test classes your change can
     affect, from the VM's test map (`scripts/testmap.py`): what each test class ran, with the bytecode hashes, recorded
     on every Gradle run on the VM. A fresh VM has no map, so its first `--affected` runs everything. It is not the gate:
     run the full check before you report, after merging main.
   - **One fixture:** `scripts/vm-gradle.sh :compat:oracleTest --tests '*.RouterOracleTest'` starts one JVM for it
     (build-logic's `TestCandidates`), so it takes seconds. Neither it nor `--affected` may drop a test: both find test
     classes through their supertypes (jars included) and composed annotations (`@Test` as a meta-annotation), and keep
     any class with a supertype or annotation type they can't find. A full check runs the same cases with
     `-Pshard.testCandidates=off`.
   - **No breaks.** Mutation passes (`scripts/breaks/`) are dropped (2026-11-01): slow for what they found
     (note 44). Instead, give each decision your fixture claims to check its own
     assertion: a state that only that rule produces, compared with vanilla or checked in a unit test. Reviews after
     each wave check that fixtures test what they claim. The old scripts and logs stay as history; don't add new ones.
4. **Pass your gates.** Each area has a tier (A–E, `ARCHITECTURE.md` §1) and gates (L1–L5, §6). The brief says
   which ones you must pass. A gate is passed when a committed test or script shows it, not when the code looks
   right.
5. **Commit** in your worktree, on your branch, in small coherent commits (format below). Leave the tree clean.
6. **Report** in the format below. The lead merges branches in dependency order, so the report is the hand-off.

## Reference

### Code

- Java 25 language and class files (`--release 25`), built and run on JDK 27. Package root `com.xpdustry.shard.<module>`. Follow the idiom of the code already in the module.
- Simulation code in `:sim` is **deterministic**:
  - world state comes only from the `World` and its `Rand`;
  - time only from the tick;
  - math only from `:common`'s Arc-exact helpers;
  - ordered collections only (never iterate a `HashMap`/`HashSet`);
  - no threads.
- Vanilla is the spec for Tier A/B behaviour. Cite it in comments as `Conveyor.java:123` when porting a non-obvious
  rule, so a reader can check it.
- When you port a vanilla quirk on purpose, mark it with a one-line comment that names the quirk.

### Tests

- JUnit 6 (`libs.versions.toml`). Oracle tests use `testImplementation(project(":tools:oracle"))` and `forkEvery = 1`. The API cheat
  sheet is in `tools/oracle/README.md`.
- Wait for an event with a count (messages, ticks, frames) or one long failure detector (`Hang`, 3 min), never a
  deadline of seconds: a deadline that passes alone fails under the full check's load (W4.8).
- Tag slow suites `@Tag("slow")`; tag tests that need a vanilla client `@Tag("client")`. Untagged tests must run in
  under a minute per module.
- One `scripts/vm-gradle.sh` run at a time per VM: a second one fails at once naming the run that holds the VM; pass
  `--wait` to queue behind it, or `--peek '<cmd>'` to look around (reports, logs) without syncing.
- A test that walks class directories, or reflects over classes it never runs, belongs in `src/test`, not
  `src/oracleTest`: the test map (`--affected`) counts only the classes an oracle test initializes.
- A behaviour claim ("matches vanilla") needs a differential test against the oracle, at a stated tolerance and
  tick count.

### Commits

```
<Imperative subject, under 60 chars>

* what changed, one line per change
* ...

> Committed on behalf of <GitHub user> by <your model name>
```

Never add a `Co-Authored-By` trailer.

### Final report

```
Branch: <name>   Head: <sha>   Base: <sha you started from>
Done: <what works, one line each, with the test or command that shows it>
Gates: <L1/L2/L3/L4/L5 passed or not, with the command>
Deviations: <every break from ARCHITECTURE.md, with the reason; or "none">
Requests: <changes you need in paths you don't own; or "none">
Gaps: <what is missing or known wrong>
Vanilla: <bugs and odd behaviours you found in vanilla, each with file:line, the kind (bug, nondeterminism, unsafe
  input, quirk) and what Shard does (kept, fixed, pinned in oracles, refused); or "none">
Cleanup: <VMs deleted, daemons stopped>
```

The lead records each `Vanilla:` entry at merge.
