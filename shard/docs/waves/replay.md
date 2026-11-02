# Replay

Read before touching `replay/**`, `tools/replay`, `shard replay`, the view stream or `.sview` format, `replay.record`,
or checkpoint retention. Rows: RP2 (it has no row table; its text is below). Index, ownership rules and live contracts:
[`WAVES.md`](../../WAVES.md). Paths are relative to the repository root.

**Replay (side quests done: note 37, `docs/replay/FORMAT.md`, a browser viewer (not in this repository), `tools/replay`; RP2, live replays)**
- Recommendation: archive the replay bundle (checkpoints, command log, hashes; 5–27 MiB/h); watch in a browser
  through the view stream and the TS/WebGL2 viewer (6–12 MiB/h, static hosting); `ReplayService` regenerates
  segments byte-identical from a bundle (31/31 at 111 ms) and bakes them before a build retires; the vanilla-client
  replay world later (0.1–1.2 s per seek). Shard-in-WASM was dropped on 2026-11-02: the viewer needs no sim.
- Requests (note 37 §7; the demo needs none): a bullet exposes its `BulletDef` and spawn tick, and units and
  turrets `isShooting`/aim (W2.8, turrets); construction-site state (construction); building-destroyed and
  wave-started `WorldEvent`s (W2.8 or construction, W2.11); player join/leave/team and chat events (W2.12, W2.14);
  checkpoints at multiples of 600 ticks kept for the match, with the command log split at the same ticks, which
  needs the W1.4 gaps below closed; one read-only `SYNC` observer slot (W2.13).
- Done (RP2, replays from the live server):
  - with `replay.record`, each hosted match is recorded as a bundle: a checkpoint every 600 ticks with the inbox's
    waiting inputs, the input log split at each, the world hash at each, and `.sview` segments;
  - view format 2 (epoch tables, a SHA-256 per segment, typed sections), with players, chat, plans, bullets, aim,
    fires, puddles, construction sites, turret targets and rebuild plans;
  - `replay.record` and `persistence.match-checkpoints` are per world and reload live (W6.6): a world's checkpoint
    interval comes from its own effective config (600 ticks when recording and unset), each tick. A reload that
    turns recording on starts it at that world's next match checkpoint, in the match under way; turning it off ends
    the bundle there (`ended`, no winner, view stream baked; not `suspended`, which expects the same ticks to follow),
    and the match records no more. A bundle keeps the `checkpointEvery` it started with;
  - `bin/shard replay list|verify|export|bake`;
  - a reload or a stop ends the match (`ended`), and the next map starts a new one, which `persistence.resume` never
    resumes.

  Found and fixed: a join's capture unparked waiting processors (`Processor.save`), which changed the hashed
  world. `ObservationTest` stages every codec kind on all 18 maps and checks that no reading changes the world
  (ARCHITECTURE §4). Live gate: 12 min of play with 6 bots and 43 joins, a SIGTERM and a resume; `shard replay verify`
  gave 73 of 73 segments identical and 144 hashes equal (`tools/replay/gate/live-gate.sh`).
- Gaps: AI targets aren't recorded. The recorder costs about 1.8% of a core at 6 players on a 256² map: maps
  rebuilt and the terrain scanned every frame, and the command log copied every tick. `shard replay` subcommands
  take `bin/shard`'s JFR options. Checkpoints in a browser; Safari untested. Rig items from the vanilla-client spike: expect the reload,
  a multi-connection probe, a camera-moved check.
- Decided (2026-11-01): keep replays as they are (bundles live with their build, `.sview` forever); no
  access rules for PvP replays and no in-game replays for now. Don't spend design time on it (note 37 §8).
