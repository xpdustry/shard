# The server: connect flow, hosting, tick cost, world loop

Read before touching `server.host`, `server.session`, `server.connect`, `server.sched`, `server.world`, `net/**`,
startup, AOT, the load gate or tick-cost work. Rows: W1.4, W1.6, W2.4, W2.4h, W2.21, W4.5, W4.6, W4.8, W4.9. Their owned paths and WPs are in
[`history.md`](history.md). Index, ownership rules and live contracts: [`WAVES.md`](../../WAVES.md). Paths are relative
to `shard/`. Intents and snapshots: [`sync.md`](sync.md).

**Connect flow, sync and the server (W2.4, W2.4h, W2.13 and W2.14 done)**
- Done (W2.4): the `shard` binary from config with the Shenandoah launcher; the connect flow with a test per
  `KickReason`; bundled maps hosted as worlds with mailboxes, seats and `/world` transfers on one connection;
  `PathRules` pushed from the rules model; the console; `server.dev` retired; queued logging. L5: 18 maps 95/95 and
  two clients with a reconnect 40/40 at 605269d; 1,000 bots in 100 worlds with 0 ticks dropped.
- Done (W2.4h, server hardening):
  - Review 38's six server findings, each with a test that failed first.
  - Review 39's capture budget, which reserves a join's measured footprint, and its stale-encoder fencing.
  - A capture or encode failure kicks only its session.
  - Kicks go out after the tick's Calls.
  - An observer overflow resyncs every member.
  - The server hosts every startup world before it listens.
  - `game.universe-seconds` for the state snapshot.
  - The scheduler runs on an injected clock, and match checkpoints are checked per tick. A catch-up turn used to
    skip them.
  - The server tests' timing flakes are gone: virtual clocks, waits on events, and no fixed ports.
  - AOT on JDK 27: `bin/shard --aot-train`, and the cache is used when present. The first join's longest tick went
    from 8–16 ms to 2–8 ms.
  - L5 on `bb3ca445`, with the cache trained: `shard-two-clients` 48 steps, including the core's loadout and wave
    1's countdown against vanilla's; `shard-loads` 30; `shard-maps` 96 on a rerun.
- Open (W2.4h):
  - `shard-maps`' first run had archipelago start a tick 1.24 s late, right after an autosave. It is unexplained:
    alone, the fork takes 1.4–6.9 ms. The same run had a 345 ms freeze of every thread outside the JVM.
  - The autosave's fork runs in `boundary()`. Fixed by W2.21: `boundary()` has its own histogram and the watchdog
    watches it.
  - `WaveFields`' preload took 30–45 ms of archipelago's first tick. Fixed by W2.21: the fields are built in
    `Host.load`.
  - The capture footprint's constant overheads are measured, not derived.
  - `ArcClientTest` ran on the system clock. Fixed by W4.8: it binds with a virtual clock like `TransportTest`, and Arc's
    own read timeout is off.
- `TeamUnits.recount` is 43% of a stone world's tick (with W2.12).
- Tick cost (W2.21 done): where the tick goes, per system, is in `tools/bots/load/results/README.md` (W2.21).
  Vanilla's own work (snapshot and plan-preview sends, unit pushes) is most of it. The accidental costs are fixed:
  - unit grids count over their entries' bounds;
  - `World.find` is a hash table, and content getters are cached;
  - the tiles' hash is reused until a tile changes;
  - team stats visit only teams with cores;
  - `AvoidanceProcess` clears only what it stamped (`AvoidanceOracleTest`, exact for 900 frames);
  - the outbox and roster walks are gone;
  - the TCP readers' LZ4 table is made once.

  Worlds are woken outside the session and mailbox locks (RP2's stall). In the load A/B against main, tick p50 is
  lower in all 12 pairs (7–51%) and CPU in 11. The golden and `TickCostBench`'s sent-byte digests are unchanged, and
  all 21 breaks fail. `profile.sh`, `JfrProfile` and `perf profile` give CPU by system and phase.

  A watchdog test failed 1 in 3 full checks, never alone: `ManualClock` counted a thread as caught up before its
  first park, so on a starved VM the clock passed a whole stall before the watchdog had started. Fixed (`da14bb32`):
  the scheduler names its watchdog to the clock before starting it, and a read counts only once the thread parks
  again. `theClockWaitsForALateWatchdog` and `ManualClockTest` gate it; all 3 breaks fail, and under 8 busy loops on
  4 cores main failed 1 of 10 runs and the fix none.
- Open from W2.21:
  - every tick thread sends through one `DatagramChannel` and queues on its write lock; 213–345 ms waits came with
    the dropped ticks (W4.5 gave each connection its own queue and writer, `sync.md`; the lock waits weren't reproduced,
    so the gain is structural, not measured);
  - `Intents.decode`'s per-packet string (closed by W4.5);
  - the autosave fork's allocation (W4.6: left; it is authoritative per-tile state, so cutting it needs a pooled
    copy-into fork across every part's `copy()`, a redesign);
  - `WorldManagerTest.until` waited for `idle(clock)` but not `observed()`. Fixed by W4.8: it waits for both, as
    `WorldSchedulerTest` does (still no watchdog on that scheduler to observe).

**Tick allocation (W4.6 done: WP94, first pass)**
- On the five-world bench (`tools/bench/`, README there: base, mine, mine, base on one 8-vCPU VM), the tick threads
  went from 13.13 to 4.36 KiB per world tick (−67%), all threads from 4.28 to 1.76 MiB/s, and G1 from one collection
  per 60 s run to none. Golden, every oracle and every sent frame's digest unchanged.
- Per change, KiB per world tick: `TileGrid` is its own `TileSolidity` −4.10; one `Bullet` handle `rebind`-ed along
  each pass and a nesting-safe candidate buffer −1.89; `UnitView`, a reused live view `EntitySnapshots` encodes through
  −1.13; shared empty values in `UnitProjection` −0.89; `Predict` and `Angles` as scalars −0.52; `closestEnemy`'s
  reused visitor −0.24.
- Rules it set: no hook keeps a `Bullet` handle past its call; a `UnitView` is valid only until its next bind, on the
  world thread (`UnitProjection` stays what anything that keeps a unit uses); scratch fields on a world part are
  copied on fork and left out of the hash (`PartStateTest`'s skip set).
- Left, by size: the autosave fork (~4.5%), `Codecs.Mount` records per mount (~9.5% of what remains; needs an indexed
  mount accessor in WireGen's unit `Source`), `Targets.findTile`'s list (~2.5%), `Predict`'s result `Vec2` (~2%),
  `FieldEngine.repair`'s `IntSeq` growth (~1.6%), `KindKey.of`'s regex per call (`BlockDef.ownsBlockUnit:80`).

**Send path (W4.5 done)**
- The order, the bounded `Outbox` and the spam bans are in [`sync.md`](sync.md). On the connection: a 256 KiB write cap
  (vanilla's buffer is 41,000 bytes, Q241), kept, and a writer woken at the end of the tick or at 32 KiB waiting, because
  a tick's whole burst otherwise counts against the cap at once (`Conn.WAKE_BYTES`). `docs/architecture/runtime.md` has
  the ordered path.
- Open: bound world-processor Calls per world (Q243); the 256 KiB cap closes a client that a world processor floods.
- W4.11 done: the 256 KiB cap is soft. A queue over it closes only after 3 s over it (`overCapGraceMillis`); a 1 MiB
  ceiling still closes at once. `LifecycleTest`'s 275 KB chat tick had closed its chatter under load (`SlowClientTest`,
  `scripts/breaks/w4.11-slow-client.py`).

**The test loop (W4.8, W4.9 and W4.12 done)**
- W4.12, where the time went: Gradle forked a JVM for every class *file* (helpers, records, nested classes) under
  `forkEvery = 1`, and for every class under a `--tests` filter. `TestCandidates` (build-logic) forks only classes with
  tests that a filter can match (`-Pshard.testCandidates=off` restores the old behaviour). One fixture run 27 s -> 3 s;
  w4.4's break pass 842 s -> 148 s, output byte-identical. The full check stays about 6 min (5m45s cold): it is
  CPU-bound and the empty JVMs were cheap there.
- `scripts/vm-gradle.sh --affected` (`scripts/testmap.py`): reruns only the test classes whose *initialised* classes'
  bytecode changed; any non-Java change reruns everything. A one-line `:sim` break ran and was caught in 74 s, an
  edit in `:net` in 71 s, no change in 6 s. The full check is still the gate before main moves. A fresh VM has no map,
  so its first run is everything.
- One run per VM: `vm-gradle.sh` holds an `flock` for the sync and the run, so a second run exits 75 naming the
  holder; `--wait` queues, `--peek` runs read-only commands. Three rows in wave 4b lost runs to overlaps before it.
- Where an oracle class's time goes (JFR): JVM start and vanilla boot ~1.5 s per class; the map sweeps spend 40-48% in
  vanilla's `SaveIO` and 16-32% in its synchronous flowfield preload on load (Q264); comparison under 3%. Dropped:
  splitting the check across 2 VMs (30 s for twice the VM time), AOT/CDS caches, hash-then-diff comparison, faster
  simulation. Open: skipping vanilla's preload in sweeps that never step units (the test owners' call).
- W4.9, tooling: a shared Gradle build cache served from the dev box through sloppa (`scripts/gradle-cache/`; VMs read
  and push), `large` VMs by default, test forks chosen from the VM's CPUs (3 per 8 vCPUs), and
  `scripts/breaks/runner.py` for running a row's breaks in parallel (`w4.4-payload-blocks.py` is the example). A warm
  full check took 33 s against 7m53s cold. An 8-vCPU VM is CPU-bound at 3 forks: more forks gained nothing and
  failed load-sensitive tests, and parallel breaks gained 13% (17m43s serial, 15m26s at 4 jobs). A counted check runs
  `cleanTest cleanOracleTest check --continue --no-build-cache`.
- W4.8, tests only: about 320 wall-clock hits in 60 files were sorted (`scratch/W4.8/sweep.tsv`). `Hang` (3 minutes) is
  the one failure detector a server or net test waits on for something that must happen; counted bounds replace the rest
  where the thing waited for has a count. `ArcClientTest` runs on a virtual clock, `WorldManagerTest.until` waits for
  `observed()`, and `m0.rig` fails on any server WARN except `watchdog stall` and `ticks behind`.
- Flakes fixed on the way: `Conn` stamped `lastWriteNanos` after the write (`TransportTest` and `ArcClientTest` wait on
  `queuedBytes() == 0`); `GroundUnitsTest` called `waitForPaths` after `step(1)` and raced vanilla's pathfinder thread;
  `SendOrderTest` failed 3 of 40 under stress (now `Hang` and `Conn.wakes()` assertions).
- Gates (W4.8): three consecutive full checks with `--no-build-cache` on `8ed20e8b` (8m10s, 6m41s, 7m39s, nothing from
  cache); one under `stress-ng --cpu 8` (14m07s); `:net:test` 60/60 under stress.
- Gaps (W4.8): absence windows are left short (they can't fail under load, but can miss a late event; virtual-time
  rewrites of `WorldManagerTest` would fix it); `LifecycleTest` waits 3 minutes now, so a timeout there is a real stall;
  `m0.rig` wasn't rerun.
- Closed by W5.8: `Server.Options.netClock` is the transport's clock seam; `ServerTest.aQuietClientOutlivesTheReadTimeout`
  moves it 14 s ahead with a keepalive every 2 s, and a silent canary's close shows the sweep ran. Perf, after the PoC: the oracle JVMs' flags (JIT and GC threads,
  `ShardExtension`), and more vCPUs for the breaks.

**World loop and ops (W1.4)**
- Inbox inputs at a checkpoint and arrival numbers are stored (RP2), and commands that hold buildings or entities
  serialize by identity. Timer tasks must be records or named classes. `BuildPlan.config`
  checkpoints only a fixed list of types.
- No `/shard profile` yet (`TickStats.profile()` has the data); no demotion of overloaded worlds, CPU budget config
  or map rotation (WP48). Zstd/lz4 checkpoints weren't measured (deflate 1: 6.55 MB → 502 KB in 10.7 ms).
