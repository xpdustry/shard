# Subsystems: pathfinding, physics, logic, logistics, combat, construction, persistence

Detail for `ARCHITECTURE.md` §9, binding like the rest of it; `§N` in this file means a section of
`../../ARCHITECTURE.md`. Open it when you build or change one of these areas; read only its block. Paths are relative to the repository root.

**Pathfinding (22):**
- Dial's bucket-queue Dijkstra for full builds, plus incremental subtree repair, with vanilla's cost formulas.
  Repair is checked against a full reference rebuild (equal to the fresh shortest-path problem, not to vanilla's
  stale live fields). Tie breaks are deterministic, and unreachable regions are tested.
- One `PathService` per world, with no path threads.
- RTS orders use vanilla's `ControlPathfinder` (W3.11): hierarchical A* over 12×12 clusters per team and cost type,
  with portals and per-goal fields. Vanilla's thread loop runs on the tick instead, in `ControlFlush`: a pass every
  2 ticks, with a measured pop budget per field, and the invalid check and recompute window counted in ticks. Path
  costs are equal to vanilla's on all 68 bundled maps.
- Unit `Brain`s run at a low rate and write an `Intent`. Steering runs every tick: field descent, then line-of-sight
  smoothing, then lane spreading.
- Turret-threat and clearance layers are rules, off by default.

**Physics (29):**
- One `SpatialIndex` per world: per-team, per-physics-layer counting-sort grids (cell 32) for units.
- Physics pushes are the exception: each physics layer uses `BodyTree`, a node-for-node port of Arc's `QuadTree`,
  because vanilla's push results depend on its candidate order (W2.10). Crowd pushes are then bit for bit. Cost per
  step: 68 µs at 500 bodies (below the grid's), 2.56 ms at 5,000 settled bodies (vs 0.71 ms for the grid and 4.8 ms
  for vanilla's own tree).
- Buildings use flat per-tile arrays, solidity bitsets and 8×8 chunk buckets per team. The bullet grid is lazy.
- Copy vanilla's rules exactly:
  - hitboxes;
  - the diagonal Bresenham walk for bullets, and the 4-connected walk for lasers and splash;
  - the swept AABB test;
  - target priority;
  - splash falloff.

  No DDA.

**Logic (28):**
- The interpreter only; the compiler stays behind a flag.
- Links are event-driven, and sleeping processors park until their timer fires.
- Execution happens in the building's turn (§3).
- CI fuzzes against vanilla's `LExecutor`.
- `LogicBuild` saves are version 5, byte for byte, including accumulators and timers.

**Logistics and power (30):**
- Conveyors are compatible segments with exact immediate handoffs. Whole-line batching changed throughput by 3.3%,
  so it stays research only.
- Power uses union-find merges, rebuilds only the affected graph on a split, and keeps ordered roles.
- Liquids use ordered local transfer edges with every vanilla quirk.

**Combat (31):**
- Bullets, weapons and abilities are data: ordered hook lists, plus named native behaviours for the special cases.
- Damage and death are decided on the world thread alone. Unsupported content is reported explicitly.

**Construction and economy (32):**
- An intent executor (§4), with one treasury per world and team and explicit containment tables for payloads.
- v160.7 plan limits, construction arithmetic and per-type unit caps are exact.

**Persistence and ops (34):**
- msav readers for v1–13, starting with the versions the bundled maps use (v2, v4, v5, v7). Other versions are
  rejected clearly until their codec passes. The writer is v13.
- Layered, typed YAML config. SQLite in WAL mode with a single writer holds players, bans and notes.
- Autosave every 60 s. A loopback REST/SSE admin API, JSON logs, Prometheus metrics and health checks.
