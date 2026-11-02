# Tier A and Tier B exceptions

Detail for `ARCHITECTURE.md` §1, binding like the rest of it; `§N` in this file means a section of
`../../ARCHITECTURE.md`. Open it when you pin an oracle fixture to vanilla, write a Tier A or B gate, or port anything
vanilla times by the clock or by another thread. Paths such as `tools/oracle` are relative to the repository root.

**Where vanilla isn't reproducible with itself, Tier A narrows** (review 36). Each exception is listed here:
- **JSON inside the world stream and Calls** (rules, objectives, markers): vanilla writes `ObjectSet<Block>` and
  similar in identity-hash order, which changes between JVM runs. The requirement is that vanilla parses Shard's
  JSON to equal state. Field order follows `JsonIO`.
- **The world stream** as a whole is gated by L3: vanilla decodes it to the same state. Its binary sections are
  byte-exact; only the embedded JSON above and `PayloadSeq` below are not.
- **`PayloadSeq`** (a unit assembler's received payloads, in saves and block snapshots): vanilla writes its
  `ObjectIntMap` in identity-hash order (`mindustry/type/PayloadSeq.java:86`–`:94`, Q265). Shard writes first-added
  order. The requirement is that vanilla reads Shard's bytes to equal counts; the oracles pin vanilla to first-added
  order to compare bytes.
- **Processor `sync` cadence:** vanilla sends `syncVariable` after more than 50 ms of wall time
  (`LExecutor.java:2077`). Shard uses a tick count, which is Tier C timing. The payload is Tier A.

**Where vanilla isn't reproducible with itself, Tier B narrows too** (review 38):
- **Proximity order:** vanilla builds a building's `proximity` by iterating an `ObjectSet` keyed on identity hashes
  (`BuildingComp.java:1886`), so routers, `dump` and `offload` pick neighbours in an order that differs between
  JVM processes, and a vanilla server and its own clients already disagree. Shard uses first-seen edge order. Oracle
  tests pin vanilla to that order (`tools/oracle` `VanillaOrder`) and prove the semantics under it; they don't
  prove that a stock client agrees, and no order could. Nothing corrects this state for non-synced blocks, in
  vanilla or Shard; repairing it with block snapshots is the §8 experiment. The sync rows measure Shard's divergence
  from stock clients against vanilla's own and must be no worse.
- **Process-wide history, kept per world** (review 39): vanilla keeps some gameplay history in statics that outlive a
  map:
  - `Lightning.lastSeed`, which shapes every lightning branch (`Lightning.java:25`, `:43`);
  - the capacity of `Damage.damages`, which orders area damage;
  - `Unloader`'s `ContainerStat` pool;
  - the entity pools (fires, puddles, bullets). `SaveIO.load` frees the old world's entities into them, and their
    `reset()` draws and ids then land in the loaded world's first frame (W2.18).

  Shard keeps each of these per world and checkpoints it, so a world matches a vanilla server that hosts it alone
  from a fresh process. Oracle fixtures reset vanilla's statics to make that comparison (`HazardPools.empty()`
  after a load). Stock clients run
  `Lightning.create` too, with their own seed, so a vanilla server and its clients already draw different lightning.
  Damage and health are server-side and corrected by snapshots (Tier C). No stock-client state depends on the
  server's history here.
- **Wall-clock and thread timing** (review 40): some vanilla work is timed by the clock or by another thread:
  - construction's place sound draws from the shared random depending on the milliseconds since the last sound
    (`ConstructBlock.java:123`–`:143`), so the number of draws depends on frame timing;
  - the pathfinder runs on its own thread, refreshing at most every 100 ms (`Pathfinder.java:103`–`:111`);
  - fog redraws on two drawing threads, gated on 40 ms of wall time (`FogControl.java:274`, `:318`–`:437`; W4.3, Q226);
  - a unit group's formation is computed on `mainExecutor`, a pool thread, so it is ready some wall time later
    (`UnitGroup.java:44`; W4.2, Q236).

  Shard runs all of these by tick, at vanilla's nominal 60 frames a second, with the path flush synchronous. Fog redraws
  every 3 ticks, drawn synchronously in `TEAMS`, and a formation is computed at once. Oracle fixtures
  pin vanilla to that schedule (`BuildRun`, `VanillaPathfinder`, `VanillaFog`, and a same-thread `mainExecutor` in
  `CommandRun`). The gates prove behaviour under the nominal
  schedule. A loaded vanilla server drifts from it, and so from itself. The shared random is server-side: clients
  draw their own, so no stock-client state depends on it. AI and pathfinding are Tier D: outcomes are judged against
  unmodified vanilla on the rig (`wave-ai.rig`).
