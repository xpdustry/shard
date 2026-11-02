# Five-world allocation bench

What Shard allocates while it runs five busy worlds with 20 players, and where. W4.6 (WP94) built it from the
measurement that found Shard allocating about 4.6× what five vanilla servers do; use it for any change meant to cut
per-tick allocation, and A/B it against the change's base.

## Rerun

From the dev box, in `shard/`, against a base commit (here W4.6's base):

```sh
SHARD_VM_TYPE=large LABEL=<label> tools/bench/bench-boat.sh 9ae67530
```

It uses this checkout's `scripts/vm-gradle.sh` VM, which must be 8-vCPU (`SHARD_VM_TYPE=large` when it is created;
delete a smaller one first with `scripts/vm-gradle.sh --delete`). It builds both servers there, runs base, mine, mine,
base (`ROUNDS=2`; about 2 minutes a run), and copies the text results to `results/<label>/`. The JFR recordings stay
on the VM in `~/bench-out/<side>-r<round>/server.jfr`. Delete the VM when you are done.

On a VM that already has both trees, one run: `python3 tools/bench/bench.py <out-dir> <tag> [--server <bin/shard>]`;
then `python3 tools/bench/analyze.py --sites 30 --depth 3 <out-dir>/<tag>` for more sites and their callers.

## What a run does

- `activebench.msav` (written by `ActiveMap.java`; its comment has the command) in five worlds of one server: a world
  processor placing routers, a conveyor line, a lancer, and 12 daggers a wave every 10 s.
- 20 `shard-bots --walk` bots, four a world: they walk, shoot, chat and build conveyors. The worlds tick from their
  first join.
- Server on CPUs 0-3 (G1, 512 MiB to 1 GiB heap, no AOT cache), bots on 4-7, over loopback.
- 30 s warmup, then 60 s of JFR (`settings=profile`) and the console's `perf mark` … `perf summary`.

## Reading `summary.txt`

One `RUN` line per run, then its top allocation sites:

```
RUN base-r1    ticks=18053 dropped=12 tick p50=0.175 p99=3.327 ms total=4.290 tick=3.827 MiB/s tick=13.02 KiB/wtick gc=1 pause=73 ms
     73.38 MiB  28.6%  TileSolidity.grid:27  TileSolidity$$Lambda
```

- `total` and `tick` MiB/s come from JFR's per-thread allocation counters (exact, not sampled); `tick` is the
  tick threads only.
- `KiB/wtick` is the tick threads' allocation per world tick. Compare this one: the waves grow over the run and runs
  drop different numbers of ticks, so MiB/s moves with them.
- `gc` and `pause` count the collections in the recording. A 60 s run sees zero to two, so read them over several
  runs.
- Sites are JFR's allocation samples, weighted: estimates. Use them to find sites, and the counters to measure.
- The trial behind this bench saw the VM's own I/O stalls drop hundreds of ticks in some runs: compare the
  alternating pairs, and report the dropped ticks with the numbers.

## Results

`results/<label>/`: what `bench-boat.sh` copied back. W4.6's runs, against main `9ae67530`, on one 8-vCPU VM:

- `1-tiles`: `TileSolidity` alone, base/mine/mine/base.
- `2-all`: every change up to `cfb11697` (the unit view), base/mine/mine/base.
- `3-series`: one run per commit, forward then reversed (14 runs).

Tick threads, KiB per world tick (the mean of `3-series`' forward and reversed runs):

| Commit | Change | KiB/wtick | Saved |
|---|---|---:|---:|
| `9ae67530` | base | 13.13 | |
| `b5067f92` | the grid answers `TileSolidity` (no lambda per lookup) | 9.03 | 4.10 |
| `941dd95d` | unit projections share empty values | 8.14 | 0.89 |
| `abfa02bd` | one bullet handle and candidate buffer per pass | 6.25 | 1.89 |
| `08ac6364` | `Predict`'s roots and `Angles`' vector as locals | 5.73 | 0.52 |
| `cfb11697` + `4c0d0500` | entity snapshots encode units through a reused view | 4.60 | 1.13 |
| `9225db80` | `closestEnemy`'s visitor reused | 4.36 | 0.24 |

All the threads: 4.28 to 1.76 MiB/s (−59%); tick threads 3.83 to 1.28 MiB/s (−67%). G1 collected once a run
before (11–62 ms) and never after. Tick p50 and p99 moved with the VM more than with the code (the reversed half of
`3-series` ran 2–4× slower on every commit), so these runs show no latency claim either way.
