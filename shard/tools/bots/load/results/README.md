# Load gate: 1,000 bots in 100 stone worlds (W1.6h)

`SHARD_VM_TYPE=large tools/bots/load/load-boat.sh` on an 8-vCPU boat VM, 2026-10-29, at W1.6h (per-session
mailboxes, handshake and frame deadlines). Setup as in W1.6: DevServer (1 GiB heap, 3 tick threads) on CPUs 0-3,
shard-bots on CPUs 4-7, over loopback. Each bot sends a clientSnapshot every 66 ms, a ping every second and a chat
line every 30 s. The summary covers seconds 20-90.

| case | joined | dropped / kicked | server CPU (of 1 core) | tick-start lateness p50 / p99 / p999 / max | ticks dropped |
|---|---|---|---|---|---|
| W1.6 `b1000-w100-t0` | 1000 | 0 / 0 | 21.9% | 59 µs / 71 µs / 14.3 ms / 39 ms | 0 |
| W1.6h `b1000-w100-t0` | 1000 | 0 / 0 | 21.4% | 55 µs / 87 µs / 26.6 ms / 138 ms | 632 |
| W1.6 `b1000-w100-t30`, transfers | 1000 | 0 / 0 | 26.9% | 59 µs / 79 µs / 18.4 ms / 43 ms | 0 |
| W1.6h `b1000-w100-t30`, transfers | 1000 | 0 / 0 | 21.6% | 55 µs / 71 µs / 20.5 ms / 48 ms | 0 |

- No mailbox coalesced or dropped a snapshot (`coalesced=0 mailboxDropped=0` in every report line), and no
  connection hit a deadline (`deadlines=0`). 2,315 of 2,336 transfers finished; the rest were under way at the end.
- The 632 dropped ticks in `t0` come from one 138 ms G1 pause at 84 s (`b1000-w100-t0.gc.txt`). It promoted the same
  heap as every other run's last pause (773 MB -> 246 MB; W1.6's took 25-45 ms), and the hypervisor stole 3 CPU-seconds
  in that run against 0 in `t30`. A pause over 50 ms puts every world more than 3 ticks behind, so each drops one.
- An earlier run, right after a full `check` with its Gradle daemon still resident, had pauses of 68 and 76 ms and
  dropped 45 and 180 ticks. `load.sh` now stops the daemon after its build. A first VM was unusable (84 CPU-seconds
  stolen per run, 12% CPU and 1.5 ms p99 with no players at all).

## A/B against W1.6 on one quiet VM (`ab/`)

`tools/bots/load/ab.sh`: W1.6's code (`5f6f4fa`) and W1.6h's, alternating, daemons stopped, on one VM (0-2
CPU-seconds stolen per run). This is the like-for-like comparison.

| case | W1.6 CPU / p99 / ticks dropped | W1.6h CPU / p99 / ticks dropped |
|---|---|---|
| no transfers | 21.8% / 87 µs / 0 | 19.8% / 71 µs / 0 |
| transfers every 30 s | 20.0% / 71 µs / 0 | 22.1% / 79 µs / 0 |

The differences go both ways and are within run-to-run noise: the mailbox costs no measurable CPU. Each run had 6 G1
pauses, the heap after each pause was the same within 1 MB, and the longest pauses were 46 / 36 ms (W1.6) and
30 / 34 ms (W1.6h).

# W2.4: the real `shard` binary

`SHARD_VM_TYPE=large LABEL=w2.4 tools/bots/load/load-boat.sh` at `f287284`, 2026-10-29, on an 8-vCPU boat VM (1
CPU-second stolen per run). The installed `bin/shard` (Shenandoah generational, the launcher's default; heap
pre-touched) on CPUs 0-3, shard-bots on CPUs 4-7, over loopback, with W1.6h's bot workload. The server joins each bot
to the world with the fewest players. The summary covers seconds 20-90 after the bots start (`perf mark`, then
`perf summary` on the console), and so does the GC summary (`gc-summary.sh`). Outputs in `w2.4/`.

- **Case 1**, W1.6h's workload: 100 copies of W1.6's 50x50 stone world (`MAPS=stone`, written by `StoneMap.java` and
  hosted like any map), a 1 GB heap.
- **Case 2**, the new baseline: the 18 default maps over 100 worlds (`MAPS=default`), a 6 GB heap.

| case | joined | server CPU (of 1 core) | lateness p50 / p99 / p999 / max | ticks dropped | longest GC pause (collector) | heap after GC |
|---|---|---|---|---|---|---|
| W1.6h `b1000-w100-t0` (DevServer) | 1000 | 21.4% | 55 µs / 87 µs / 26.6 ms / 138 ms | 632 | 137.5 ms (G1, young) | 20 → 246 MB, rising (young pauses only) |
| W1.6h `b1000-w100-t30` | 1000 | 21.6% | 55 µs / 71 µs / 20.5 ms / 48 ms | 0 | 47.8 ms (G1, young) | 20 → 263 MB, rising |
| case 1 `stone-b1000-w100-t0` | 1000 | 38.3% | 55 µs / 71 µs / 95 µs / 3.7 ms | 0 | 0.3 ms (Shenandoah) | 93-94 MB |
| case 1 `stone-b1000-w100-t30`, transfers | 1000 | 39.9% | 55 µs / 71 µs / 103 µs / 1.8 ms | 0 | 0.2 ms (Shenandoah) | 93-97 MB |
| case 2 `default-b1000-w100-t0` | 1000 | 38.6% | 55 µs / 71 µs / 103 µs / 3.8 ms | 0 | none in the window (Shenandoah) | 511 MB at the last cycle, 17 s before |
| case 2 `default-b1000-w100-t30`, transfers | 1000 | 54.1% | 55 µs / 71 µs / 383 µs / 4.9 ms | 0 | 0.3 ms (Shenandoah) | 545 MB |

- The tail is gone: p999 lateness is 95-383 µs against W1.6h's 20-27 ms, and no world dropped a tick. Every
  Shenandoah pause was under 0.4 ms, where G1's young pauses took 14-138 ms.
- The live set is flat: 93-97 MB for 100 stone worlds, about 545 MB for 100 real maps (`ready` reports 186 MB and
  994 MB in use before the first cycle). 2,309 and 2,313 transfers finished in the two transfer runs; the rest were
  under way at the end. A world stream is 5.0 KB (stone) and 21.7 KB (default maps) per bot.
- The real binary sends no entity snapshots yet (no units until WP23, no entity sync until WP25), so each bot gets
  40 B/s over UDP (state snapshots) where W1.6h's DevServer sent 2.6 KB/s. Its network load is lighter.

## Why case 1 costs more CPU than W1.6h

Case 1 runs W1.6h's workload at 31-38% of a core against W1.6h's 21-22%. The extra cost is the simulation: W1.6h's
stone world ran one system (`StonePart`, an air-only content); the real binary runs the whole hosted system list on
vanilla content in every world, players or not.

- **A/B on one VM** (`tools/bots/load/ab.sh`, `w2.4/diag/ab-*`): base DevServer (`523df37`) 21.7% and 23.7% at t0,
  the real binary 31.4% (`605269d`) and 36.6% (`28450b9`). Tick time p50 is 1-2 µs for DevServer, 8-10 µs for the
  real binary. My own late changes made it cheaper, not dearer: `605269d` against `f287284`, alternating on one
  VM (`w2.4/diag/ab-605269d-vs-f287284`), 37.3% against 30.8% at t0 and 43.8% against 39.2% at t30, tick p50 11-12
  against 7-8 µs (the sampled per-phase CPU below).
- **The worlds alone** (`BOTS=0`, `w2.4/diag/idle.server.txt`): 100 empty stone worlds cost 6.0% of a core, tick
  p50 7 µs.
- **Profile** (`w2.4/diag/profile.txt`, JFR execution samples of a stone t0 run): the tick threads hold 70% of the
  samples, and 79% of theirs are in `World.tick`. `sim.unit.TeamStats` takes 43% of tick time:
  `TeamUnits.recount` clears all 256 teams' unit lists every tick, unit or not (`Arrays.fill` from `UnitSeq.clear`,
  33.5% self), about 51,000 scattered objects a round over 100 worlds. `sim.unit.PlayerUpdate` takes 24%. The
  scheduler's per-phase CPU accounting took 17% of tick time (`getCurrentThreadCpuTime`, a system call, at every
  phase); `8c59e0b` now samples it on one tick in 16.
- Not the cause: GC (under 1 ms of Shenandoah pauses in total per run), the network (lighter than DevServer's) and
  the hypervisor (0-2 CPU-seconds stolen per run).
- G1 (`SHARD_GC=g1`, `w2.4/diag/g1.*`, `605269d`): 36.3% CPU, longest pause 14.9 ms, no ticks dropped. Shenandoah
  costs no more CPU here and removes the pauses.
- Run-to-run spread on one VM is wide: the same binary measured 26.7-35.1% over 20 s windows, so treat single
  differences under about 5 points as noise.

The `TeamUnits.recount` cost is in sim.unit, outside W2.4's paths: W2.4's report requests that it skip teams with
no units.

## The bot that never joined (fixed in `0142cbc`)

Before `0142cbc` nearly every run joined 999 or 998 bots of 1,000. The bots now say when the server closed them,
and a run without `--quiet` logged why (`w2.4/diag/lost-bot.txt`): "protocol: Comparison method violates its
general contract!". The join sorted the 100 worlds by live seat counts that other joins changed during the sort, and
TimSort threw on the connection's reader thread. The transport took the IllegalArgumentException for a client's
protocol error and closed the connection without a warning. `0142cbc` sorts one reading of each world's seats, and
`f287284` makes a handler failure a logged error. All four runs above joined 1,000 of 1,000.

# J27: JDK 27

`SHARD_VM_TYPE=large LABEL=j27 tools/bots/load/load-boat.sh` at `883d6ee`, 2026-10-29, on an 8-vCPU boat VM (0-1
CPU-second stolen per run): W2.4's two cases on Temurin 27.0.1.0.2+1, Shenandoah generational (the launcher's
default). Outputs in `j27/`.

| case | server CPU (of 1 core) | lateness p50 / p99 / p999 / max | ticks dropped | longest GC pause | heap after GC |
|---|---|---|---|---|---|
| W2.4 `stone-b1000-w100-t0` (JDK 25) | 38.3% | 55 µs / 71 µs / 95 µs / 3.7 ms | 0 | 0.3 ms | 93-94 MB |
| J27 `stone-b1000-w100-t0` | 30.5% | 55 µs / 71 µs / 79 µs / 1.8 ms | 0 | 0.2 ms | 90 MB |
| W2.4 `stone-b1000-w100-t30` | 39.9% | 55 µs / 71 µs / 103 µs / 1.8 ms | 0 | 0.2 ms | 93-97 MB |
| J27 `stone-b1000-w100-t30` | 40.4% | 55 µs / 71 µs / 111 µs / 3.0 ms | 0 | 0.2 ms | 91-94 MB |
| W2.4 `default-b1000-w100-t0` | 38.6% | 55 µs / 71 µs / 103 µs / 3.8 ms | 0 | none in the window | (no cycle) |
| J27 `default-b1000-w100-t0` | 37.3% | 55 µs / 71 µs / 79 µs / 2.8 ms | 0 | none in the window | (no cycle) |
| W2.4 `default-b1000-w100-t30` | 54.1% | 55 µs / 71 µs / 383 µs / 4.9 ms | 0 | 0.3 ms | 545 MB |
| J27 `default-b1000-w100-t30` | 46.5% | 55 µs / 71 µs / 79 µs / 5.9 ms | 0 | 0.3 ms | 521 MB |

All runs joined 1,000 of 1,000 bots. The code differs from W2.4's (`f287284`): rows merged since then added
systems, and `ready` now reports 1.3 GB of heap in use for the default maps against 1.0 GB. So the like-for-like
comparison is the A/B below.

## A/B: JDK 25 against 27 on the same code (`j27/ab/`)

main at `61aae05` on JDK 25 (`/opt/jdk25`, its own launcher) against this branch on JDK 27, default maps, on one
VM with daemons stopped, 3 rounds with the order swapped each round. Every run had 0-1 CPU-second stolen.

| case | JDK 25 CPU | JDK 27 CPU | p99 | p999 (25 / 27) | ticks dropped | GC pauses (25 / 27) | heap after GC (25 / 27) |
|---|---|---|---|---|---|---|---|
| t0 | 45.6, 41.7, 43.0% | 44.2, 39.0, 39.0% | 71 µs in all | 87-127 / 79-87 µs | 0 | none | no cycle |
| t30 | 60.3, 56.6, 56.0% | 55.6, 56.3, 48.0% | 71 µs in all | 87-119 / 87-103 µs | 0 | 4 × ≤0.3 ms / 3 × ≤0.4 ms | 532-544 / 511-530 MB |

- No regression. JDK 27 is 2-4 points cheaper on average, within the spread W2.4 measured on one VM (about 5
  points), and its p999 and live set are no worse. Both sides ran with compact object headers (25 by flag, 27 by
  default), so the heap difference is the JDK's own.
- A first try of this A/B, right after boat's outage, lost 232-382 CPU-seconds per run to the hypervisor and read
  175-195% CPU on both sides; it was discarded. Check `hypervisor steal` in the `==` lines before comparing runs.

## The AOT cache through bin/shard (W2.4h, `aot/`)

`tools/bots/load/aot.sh` on an 8-vCPU boat VM, JDK 27, 2 GiB heap, 18 default maps, 2026-10-29. Each cache was trained
with `bin/shard --aot-train` (40 bots for 20 s: joins, chat, `/world` moves, a console kick); then 5 starts per GC and
cache, one bot joining and leaving each, and one more start with `-Xlog:class+load`. Ready is the `ready:` line's time
from launch, the launcher's own JVM probes included.

| run under | cache trained under | ready, ms | first join's longest tick, ms | classes from cache / jrt / jars |
|---|---|---|---|---|
| Shenandoah | none | 3327-3936 | 8.4-15.7 | 1,429 / 1,014 / 1,260 |
| Shenandoah | Shenandoah | 2969-3561 | 2.4-3.5 | 4,411 / 365 / 0 |
| Shenandoah | G1 | 3174-5628 | 2.5-7.6 | 4,411 / 365 / 0 |
| G1 | none | 3750-5343 | 9.3-13.5 | 1,429 / 1,014 / 1,260 |
| G1 | G1 | 3366-5199 | 3.3-7.9 | 4,411 / 365 / 0 |
| G1 | Shenandoah | 3214-5173 | 2.8-15.8 | 4,411 / 365 / 0 |

- Either GC's cache serves both, as J27 found. The first join's longest tick is where it shows: the classes it
  loads come from the cache instead of the jars. Ready moved by about 0.5 s, within a wide spread on this VM.
- Training took 34-38 s; the JVM wrote the 38 MB cache 4-9 s after the server stopped, and recorded no JFR.
- A cache trained without JFR's options is refused whole by every run with them ("Mismatched values for property
  jdk.module.addmods: jdk.jfr"): 0 classes came from it. `--aot-train` keeps them.
- The default path: `--aot-train` wrote `data/aot/shard-<build>-<key>.aot`, and a plain start loaded the same 4,411
  classes from it.
- On JDK 25 (`aot/jdk25-summary.txt`, the same workload trained by hand) a cache trained under the other GC also gave
  every class: 4,264 from the cache, none from jars. JEP 516 (JDK 26) adds the archived heap objects to that.

# W2.4h: the load gate after main's wave 2

`SHARD_VM_TYPE=large LABEL=w2.4h tools/bots/load/load-boat.sh` at `dd9236d` (main `813e24e` merged: W2.12's game,
W2.13's entity sync, W2.14's intents), 2026-10-29, as W2.4's runs. Outputs in `w2.4h/`.

| case | joined | server CPU (of 1 core) | lateness p50 / p99 / p999 / max | ticks dropped | longest GC pause | heap after GC | steal, CPU-s |
|---|---|---|---|---|---|---|---|
| `stone-b1000-w100-t0` | 1000 | 63.4% (W2.4: 38.3%) | 55 µs / 87 µs / 479 µs / 2.6 ms | 0 | 0.3 ms | 213-345 MB | 25 |
| `stone-b1000-w100-t30`, transfers | 1000 | 53.8% (39.9%) | 55 µs / 71 µs / 207 µs / 3.2 ms | 0 | 0.3 ms | 166-247 MB | 1 |
| `default-b1000-w100-t0` | 1000 | 89.5% (38.6%) | 55 µs / 767 µs / 4.6 ms / 18.9 ms | 0 | 0.3 ms | 571 MB | 28 |
| `default-b1000-w100-t30`, transfers | 1000 | 110.6% (54.1%) | 55 µs / 383 µs / 1.4 ms / 4.3 ms | 0 | 0.5 ms | 672-732 MB | 5 |

- The server now does more per bot: each gets 2.1-2.3 KB/s over UDP on stone and 4.5-5.3 KB/s on the default maps
  (W2.4: 40 B/s), with 50-110 entities a second in its snapshots. Tick p50 on the default maps went from about 13 µs
  to 51-55 µs.
- No tick was dropped and no bot failed, dropped or was kicked; 2,318 and 2,316 transfers finished.

## A/B: main against W2.4h (`w2.4h/ab-summary.txt`)

`tools/bots/load/ab.sh` on one 8-vCPU boat VM, base main `2d71e1a`, mine `dd9236d`, alternating cases, `MAPS=default`
then `MAPS=stone`. One run each.

| case | main CPU | W2.4h CPU | steal (main / W2.4h), CPU-s | dropped (main / W2.4h) |
|---|---|---|---|---|
| default t0 | 78.1% | 75.5% | 1 / 1 | 9 / 0 |
| default t30 | 99.2% | 106.9% | 4 / 7 | 0 / 0 |
| stone t0 | 143.6% (904 players) | 75.8% | 170 / 61 | 0 / 0 |
| stone t30 | 116.3% | 63.4% | 93 / 13 | 396 / 0 |

- The rise over W2.4 is main's: on the quiet default pairs main costs what W2.4h does, 78.1% against 75.5% without
  transfers, 99.2% against 106.9% with them. The 7.7-point gap with transfers is the largest; one run doesn't
  settle it (W2.4h reserves each capture's footprint, which costs a walk of its chunks and entities per join).
- The stone pairs ran under heavy steal (13-170 CPU-seconds) and don't compare; main's drops came with its steal.

# W2.21: where main's tick time goes

The W2.4h section's gate showed main's tick cost growing 2–4× in one day (stone 8 → 19 µs tick p50, default maps
13 → 51–55 µs, CPU 38–54% → 63–111%) with no dropped ticks. W2.21 profiled main at `f0c5b23` (wave 2b) and again
at `98ea5276` (with wave 2c's construction, turrets, wave AI and review 39's fixes). It split the cost by system and
fixed what isn't inherent, with `HashTest.GOLDEN`, every oracle suite and the sync bytes unchanged. Outputs in
`w2.21/`.

**Tools.** `tools/bots/load/profile.sh` runs the gate with a JFR recording of CPU-time samples
(`jdk.CPUTimeSample`, one sample per 1–2 ms of a thread's CPU, so samples count CPU time; JDK 27, experimental),
allocation samples and per-thread allocation, stacks 192 deep. `JfrProfile.java` reads it over the gate's window
into `<tag>.profile.txt`: CPU by thread group, the tick threads' top-down tree from `WorldScheduler.turn`, a
**systems table** (the phase handler under `World.tick`, the unit component under `Unit.update` and the AI, builder or
turret class under it, the building kind, the intent under admission, the host's step around the tick, the sync's
step), self frames with callers, allocation by system, class and site, and folded stacks for flame graphs. The console's
new `perf profile` prints every world's `TickStats` CPU by phase since `perf mark`, in µs per world tick, and
`load.sh` logs it as a `PROFILE` line. Later, `profile.sh` also records monitor enters and lock parks from 1 ms, and
`JfrProfile` sums the tick threads' lock waits by lock and site. `ab.sh` takes `ROUNDS` and `CASES` and swaps which side goes first each round.

**Noise.** On 2026-10-29 between 17:00 and 19:00Z the large VMs lost 50–370 CPU-seconds per 70 s run to the
hypervisor. Under steal the JVM's CPU and the samples' microseconds both inflate (the same systems read about 2× as
many µs in a run with 203 s stolen as in one with 8 s), but each system's **share** of the tick threads held within
a few points between runs. So the breakdown below is in shares, with µs from the quietest profile run (`f0c5b23`
default maps, 8 s stolen), and the before/after comparison is an alternating A/B over rounds.

## Phase 1: the breakdown

Tick threads' share of all sampled CPU: 54% (stone) to 80% (default maps). The rest: `udp-recv` 7–20% (decoding
client snapshots into intents), the TCP writer virtual threads 6–13%, `io-cpu` 1–16% (deflating world streams, with
transfers).

**Default maps, no transfers, by system** (share of tick-thread CPU; µs per world tick in the `f0c5b23` run):

| system | `f0c5b23` | µs | `98ea5276` | inherent or accidental |
|---|---|---|---|---|
| sync: state and entity snapshots (W2.13) | 15.5% | 12.1 | 15.6% | inherent: vanilla sends both to each player every 200 ms (`NetServer.java:1322`); 75–80% of it is the UDP `send` syscall, once per recipient as vanilla's |
| sync: plan previews (W2.13) | 9.0% | 7.0 | 7.7% | inherent: vanilla sends each player's preview group to each teammate every 0.5 s, empty or not (`NetServer.java:1378`), n² per team; 95% is the syscall |
| avoidance grid (W2.17) | — | — | **12.7%** | accidental: `AvoidanceProcess.process` cleared the whole map's buffer (90,000 ints) each tick to undo a few hundred stamps. Vanilla clears it too, but on its async thread |
| team grids + recount (`TeamStats`) | 10.5% | 8.1 | 8.1% | accidental: `CellGrid.build` cleared and prefix-summed every cell (~8,300 on a default map) per grid per tick, for a few dozen units |
| all-units grid (`EntityPhysics`) | 6.9% | 5.4 | 5.7% | accidental: the same `CellGrid.build` |
| unit pushes (`PhysicsBegin`, W2.10) | 8.9% | 6.9 | 5.9% | inherent: vanilla rebuilds its `QuadTree` and pushes every tick; `BodyTree` is its node-for-node port |
| units: core, weapons, statuses, sync, velocity | 10.3% | 8.0 | 10.5% | inherent (vanilla's component updates), but content fields read by name per unit per tick (`isEnemy`, …) were accidental |
| admission: client snapshots (W2.14) | 5.0% | 3.9 | 4.3% | mostly inherent (vanilla's `clientSnapshot` checks); accidental: `buildSpeed` read by name per snapshot (2.5% of the tick), `StrictMath.pow` for a floor multiplier of 1 |
| admission: `Members.check` (W1.4) | 2.0% | 1.5 | 1.6% | accidental: a `TreeMap.put` of a new record per intent, 15,000 a second |
| `World.hash` for crash marks (W1.4) | 6.4% | 5.0 | 3.7% | Shard's own (vanilla has no hash); accidental: the tiles, unchanged nearly every time, were 80% of it |
| `TeamCoreStats` (W2.12) | 2.6% | 2.0 | 2.6% | accidental: walked all 256 teams' data each tick |
| `EnemyCount` (W2.11) | 1.8% | 1.4 | 1.2% | accidental: `isEnemy` by name per unit per tick |
| `WorldSync.beginTick` (W2.13) | 2.3% | 1.8 | 1.8% | accidental: the roster walked, wrapped and looked up per player per tick |
| part lookups (`SpatialIndex.of`, `UnitCalls.rules`…) | spread, ~3% | | | accidental: 34 helpers scanned every part with `instanceof` |
| wave AI (`GroundAi`, `FlyingAi`), builders | — | — | 1.4%, 0.6% | inherent, and small |
| turrets, buildings | 0.6% | 0.5 | 0.8% | inherent; the gate's maps have no armed turrets, so this is no turret load number |

**Stone, no transfers**: the two sync rows are 50% of the tick threads (27% snapshots, 23% plan previews, nearly all
`DatagramChannel.send`), admission 11%, `TeamCoreStats` 5%, part lookups ~8%. Stone's growth from 8 to 19 µs is
entity snapshots and plan previews, which W2.4's binary didn't send yet (40 B/s per bot then, 2 KB/s now).

**Transfers** add the world stream's capture on the tick thread (4–9% of the tick threads; the map, the rules JSON,
and accidentally a content header hashed again and an id table built again for every join) and its deflate on
`io-cpu` (9–16% of all CPU, inherent: vanilla deflates a stream per join).

**Allocation.** The tick threads allocate 19–22 MB/s on default maps: the snapshots' encoding 40% (projections,
batches, frames: inherent shape, per snapshot), the autosave's fork 30% (Shard's checkpoints, every 60 s per world),
then per-tick garbage that is accidental: `Phase.values()` (a clone per tick), `getActive`'s copy (`CoreFill`),
`Members`' records, the roster's wrappers, empty inbox drains. Off the tick threads, the TCP readers allocated
1.1 GB in 70 s on stone: a new 16 KiB LZ4 table for every ping reply (`Lz4.forThread()` on a virtual thread), for an
8-byte payload that is never compressed.

**Per player**: 10 players per world, so a world tick's 77 µs of tick-thread CPU (default maps, quiet run) is
7.7 µs per player tick, of which the per-recipient sends are 2.4 µs (inherent) and the accidental rows above about
3 µs.

## Phase 2: what W2.21 fixed

Every fix keeps the simulation's results and the bytes on the wire: `HashTest.GOLDEN` is unchanged
(`0x0cf57340ca55d1cd`), every oracle suite passes, and each fix has a test that compares it with the code it replaced
or with vanilla. Microbenchmarks are tagged `slow` (`-Pshard.tags=slow`), one fork, main and W2.21 run twice each in
ABBA order on one boat VM; each bench's javadoc has its table. `TickCostBench`, one hosted world with 10 players in the
load gate's shape, ticks in 6–17 µs against main's 12–33 µs on the four maps, and prints the same digest of every byte
it sends on both sides.

| fix | where | gate | microbenchmark, before → after |
|---|---|---|---|
| unit grids with fewer than one entry per 16 cells count-sort over their entries' bounding box instead of every cell, or sort the entries when the box is wider still (a row index keeps their queries short); crowds count over every cell as before; the same order and ranges | `sim.spatial.CellGrid` | `CellGridTest`: every range of both layouts against the counting sort over every cell | build 1.8–2.2 µs → 2–607 ns up to 200 units, a 2,000 crowd 4.7–5.2 → 4.5–4.7 µs; a 3×3 query 2.4–2.6 → 2.7–4.0 ns |
| per-world parts found through `World.find` (27 helpers) instead of scanning every part with `instanceof`, and `find` is a hash table on the class name instead of a list it scanned | `sim.**` `X.of(world)`, `sim.world.World` | `FindTest`: every class and interface of a world's parts against a scan; the oracle suites | 12 lookups: 450–515 → 93–100 ns |
| `UnitDef.buildSpeed`, `aimDst`, `isEnemy`, `entityClass` and `WeaponDef.controllable` read once per def instead of by name per call | `content.epoch.Defs` | `CachedGettersTest`, every vanilla unit | 7–30 → 0.5–1.0 ns a call |
| the tiles' hash section reused until a tile changes (a version counter on `TileGrid`) | `sim.world.World`, `sim.tile.TileGrid` | `HashCacheTest`: each kind of change, hash equals a fresh fork's | `World.hash()`: 528–940 → 106–244 µs a call (every 300 ticks) |
| `updateTeamStats` visits the teams with cores (a bitset) instead of all 256; `CoreFill` reads the active teams without a copy | `sim.team.Teams`, `CoreFill` | `TeamStatsTest` | `updateStats` 349–386 → 16–28 ns |
| `AvoidanceProcess` clears the cells it stamped instead of the whole map | `sim.path.AvoidanceProcess` | `AvoidanceOracleTest`: both buffers equal vanilla's every frame, 900 frames | in the tick bench |
| the Outbox keeps each seat's player and reads its team at `beginTick`, counts playing seats, reuses one encode buffer | `server.sync.Outbox`, `Calls`, `WorldSync` | `OutboxTest.teamsFollowTheRosterAtEachCall`, the sync byte tests | `TickCostBench`'s sync 1.7–6.2 → 1.5–4.0 µs, within its noise; the sent bytes' digest is the same |
| `Members.check` updates a member's last sequence in place instead of putting a new record per intent; an empty inbox drains without an array | `sim.world.Members`, `Inbox` | `InputTest` | in the tick bench |
| no `StrictMath.pow` when a floor multiplier is 1 (`pow(x, 1.0) == x` exactly); `Phase.values()` once | `sim.unit.MoveShape`, `World` | `HashTest`, the unit oracles | |
| no LZ4 compressor fetched for a frame under the compression threshold | `server.sync.Calls` | `OutboxTest` | |
| the content header and id table of a world stream cached per content epoch | `compat.world.ContentHeader` | the world-stream oracle tests | |
| after W2.4h merged: `Roster.of` through `World.find`, and `players()` one view instead of a wrapper per call | `server.host.Roster` | `HostInputsTest`: the view follows the roster, a fork and a restore | |
| `Frame.packet` takes this thread's compressor only to compress (it made a 16 KiB one per ping reply on the TCP readers); `Frame.udp` duplicates one view instead of making three buffers per send | `net.Frame`, `server.sync.Calls` | `FrameTest`, vanilla's bytes | |
| a world's decoding context kept on its `HostedWorld` instead of a map lookup per packet (a tenth of `udp-recv`) | `server.intent.Intents`, `server.host.HostedWorld` | the server tests | |
| the wave team's preloaded fields built at load, off the tick threads, instead of in the first tick's `PATH_FLUSH` (30–45 ms on archipelago) | `server.host.Host`, `sim.ai.WaveFields` | `HostTest`: four maps, the same hash every tick for 900 ticks through the first waves, the same fields tile by tile | |
| each turn's `boundary()` (captures, hashes, autosave forks) timed in its own histogram and watched by the watchdog; perf lines show `boundary[...]` | `server.sched`, `server.console.Perf` | `WorldSchedulerTest`: a long boundary is reported once and kept out of the tick timer | |
| an input's check of its world's mode reads it without the scheduler's one lock (`Slot.mode`), which tick threads and the server's virtual threads queued on: 1% of the tick threads on the default maps | `server.sched.WorldScheduler` | `WorldSchedulerTest` | |
| a world is woken outside the session's and mailbox's locks: the receive thread made the wake (an executor's hand-off, once a call site linking) under both, and the tick thread's pump waited on them for up to 234 ms (RP2's JFR, its bot gate's 85–274 ms ticks) | `server.session.WorldMailbox`, `Session`, `server.host.Host` | `MailboxTest`: a wake stuck in its executor holds back neither the pump nor the session | |

## The load gate: main against W2.21 (`w2.21/ab-summary.txt`)

`ab.sh` with `ROUNDS=2 CASES="stone default"` on one kept 8-vCPU boat VM with almost no steal, head `6ce1982f`
against main `8dffca92` (the same upstream code: wave 2c, W2.4h, W2.22, W2.24, W2.25), the order swapped each round.
Then each case under `profile.sh`, main first. CPU is of one core; tick and lateness are the `perf summary` window's.

| case | CPU, main / W2.21 | tick p50, main / W2.21 | tick p99, main / W2.21 | dropped, main / W2.21 | steal, CPU-s |
|---|---|---|---|---|---|
| stone t0, r1 | 62.0% / 49.3% | 19 / 11 µs | 383 / 351 µs | 0 / 0 | 7 / 0 |
| stone t0, r2 | 52.7% / 50.8% | 15 / 10 µs | 319 / 479 µs | 0 / 3,316 | 1 / 2 |
| stone t30, r1 | 59.4% / 55.9% | 17 / 13 µs | 511 / 415 µs | 0 / 0 | 0 / 1 |
| stone t30, r2 | 66.2% / 58.2% | 21 / 14 µs | 511 / 415 µs | 0 / 0 | 2 / 2 |
| default t0, r1 | 84.7% / 63.7% | 51 / 35 µs | 575 / 383 µs | 0 / 0 | 2 / 1 |
| default t0, r2 | 89.2% / 94.2% | 55 / 51 µs | 639 / 511 µs | 0 / 0 | 15 / 28 |
| default t30, r1 | 107.8% / 76.0% | 55 / 29 µs | 895 / 511 µs | 0 / 0 | 13 / 3 |
| default t30, r2 | 128.2% / 98.8% | 71 / 43 µs | 1151 / 639 µs | 0 / 20 | 15 / 4 |
| profiled: stone t0 | 60.0% / 46.9% | 17 / 10 µs | 511 / 319 µs | 2,316 / 0 | 6 / 1 |
| profiled: stone t30 | 66.6% / 61.3% | 19 / 14 µs | 575 / 447 µs | 0 / 0 | 2 / 2 |
| profiled: default t0 | 79.0% / 74.8% | 47 / 39 µs | 447 / 703 µs | 0 / 1,208 | 2 / 22 |
| profiled: default t30 | 126.8% / 85.2% | 71 / 35 µs | 1023 / 575 µs | 0 / 0 | 18 / 3 |

- W2.21's tick p50 is lower in all 12 pairs, by 7-51%, and its CPU in 11; the twelfth (default t0, r2) had the
  round's most steal on W2.21's side.
- The drops are one instant each in which every world falls behind at once, on main's runs as on W2.21's. The two
  largest match the VM's own clock stopping: a 1 Hz `date` loop beside the gate skipped 0.27 s at 20:09:27 (W2.21's
  3,316) and 0.31 s at 20:24:11 (main's 2,316), with no safepoint over 20 ms in any JVM then and no GC pause over
  0.4 ms. In the two profiled runs that dropped ticks (main's stone t0, W2.21's default t0), the longest tick-thread
  lock waits were on the one `DatagramChannel`'s write lock, 345 and 213 ms (below): a thread paused holding it
  stalls every tick thread that sends.
- An earlier A/B on another VM with 50-330 CPU-seconds of steal per run gave the same direction for tick p50 and too
  much noise for CPU (`ab-summary.txt`, bottom).

**JFR, main against W2.21** (`w2.21/systems-final.txt`, the same VM, shares of the tick threads' CPU; µs per world
tick move with steal): on the default maps without transfers `World.hash` went from 9.7% to 1.3%, the all-units grid
from 5.4% to 1.9%, the team grids from 6.1% to 4.2%, `TeamCoreStats` from 2.4% to 0.8%, `Members` from 1.8% to 0.3%;
`CellGrid.build`'s self time from 8.1% to 0.9%, the tiles hash (`Hasher.bytes`, `shorts`) and `Teams.updateStats`
(5.9%, 2.3%) are gone. On stone, `TeamCoreStats` went from 5.6% to 0.3% and `beforeTick` from 2.5% to nothing; the
two sync sends are 64% of what's left. The avoidance fix shows in `98ea5276`'s profile (12.7% to 1.4%,
`systems-98ea-vs-mid.txt`); on main `8dffca92` nothing activates the grid in the gate's window. New on top: `World.find`
2.6% self, the price of the lookups it replaced.

**Lock waits** (monitor enters and lock parks from 1 ms on the tick threads, the `profile.sh` runs):

| case | main: waits, total, longest | W2.21: waits, total, longest |
|---|---|---|
| stone t0 | 235, 1,208 ms, 345 ms (`Transport.sendDatagram` 867 ms, `Slot.mode` 154 ms, `Mailbox.drain` 13 ms) | 30, 51 ms, 3 ms |
| stone t30 | 75, 139 ms, 6 ms | 62, 120 ms, 4 ms |
| default t0 | 75, 164 ms, 6 ms (`Session.leaveDelivered` 6 ms) | 793, 2,190 ms, 213 ms (`Transport.sendDatagram` 1,664 ms) |
| default t30 | 585, 1,190 ms, 16 ms (`Slot.mode` 58 ms) | 163, 331 ms, 6 ms |

The waits on `Slot.mode` (the scheduler's lock), `Mailbox.drain` and `Session.leaveDelivered` (the receive thread's
wake under the mailbox and session locks) are gone on W2.21. What is left on both: the scheduler's lock re-taken after
a wait (`NanoClock.awaitNanos`, a few ms), and `DatagramChannel.send`'s write lock, which every tick thread takes for
every datagram; its long waits come with the runs that dropped ticks, on either side.

## What's left

Inherent, kept for the same bytes and results as vanilla:
- **The per-recipient sends**: the snapshots, every 200 ms to each player (`NetServer.java:1310-1340`), and the plan
  previews, every 0.5 s from each player to each teammate, empty ones too (`NetServer.java:1379-1414`). Shard encodes
  each once per audience; the `DatagramChannel.send` syscall per recipient is 75–95% of both and stays on the tick
  thread. Handing the sends to another thread would move the cost, not cut it; batching them (`sendmmsg`) needs a
  native call the JDK doesn't have.
- **Unit pushes** (`BodyTree`, vanilla's `QuadTree` rebuilt and queried each tick), the units' components, the
  statuses' per-tick reapply, the world stream's deflate per join on `io-cpu`.

Accidental, not fixed here:
- Every tick thread sends through the one `DatagramChannel` (`Transport.sendDatagram`), so sends queue on its write
  lock, and a tick thread paused while holding it stalls the others (213-345 ms waits in the runs that dropped ticks).
  A channel per tick thread on the same port (`SO_REUSEPORT`) or one sender thread would remove the shared lock; both
  change how the server owns its socket, so they want their own row and an L5 run.
- `Transport.byUdp`, a `ConcurrentHashMap` keyed by `InetSocketAddress`, compares each datagram's fresh address with
  the stored one through four objects: 8% of `udp-recv`, 0.8% of all CPU, off the tick threads. A table keyed by the
  IPv4 address and port would cut it; it sits next to the UDP hijack checks, so it wants its own row.
- `Intents.decode` builds `"packet " + id` for every packet (12% of `udp-recv`'s allocation), and
  `PlayerInput.viewer` allocates per snapshot (`server.intent`, W2.25). `ActionRules.of` and `UnitClusters.of` scan
  every part (W2.25).
- The autosave's fork allocates about 1 KB per world tick, every 60 s per world (`server.world`); its time is now in
  `boundary[...]`.
