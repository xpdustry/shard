# Objectives and markers

Read before touching `sim.objectives`, markers, `GameStats`, `MapLocales`, `ObjectivesJson` or the objective Calls.
Rows: W3.12. Their owned paths and WPs are in [`history.md`](history.md). Index, ownership rules and live contracts:
[`WAVES.md`](../../WAVES.md). Paths are relative to the repository root.

**Objectives and markers (W3.12 done: WP74, and WP68's markers)**
- Done:
  - `sim.objectives`: all 13 objective kinds, with their checks, parents, flags, completion code and hidden details;
  - every marker kind, in an exact port of Arc's `IntMap` order;
  - `GameStats` and `MapLocales`; typed `Rules.objectives`; the server's `Objectives` API and the 8 Calls;
  - the world processor's `makemarker`, `setmarker` and `localeprint`;
  - in `compat`: `ObjectivesJson` and the markers' UBJSON in saves.

  W5.7: `buildingsDestroyed` and `destroyedBlockCount` count on `BlockDestroyEvent`; `ObjectiveOracleTest` compares
  every stat each frame, and `gameStatsCountAsVanillas` covers destroyed buildings and `unitsCreated` through a save.
  51 bundled maps (247 objectives) and all 89 marker regions round-trip byte-equal, and every Call is byte-equal
  (`ObjectivesJsonOracleTest`). `ObjectiveOracleTest` is exact frame by frame through a save, with every objective
  kind and every marker control. `ObjectiveMapsOracleTest` keeps 11 Erekir maps exact for 1,200 frames from their own
  saves. L5: `objectives.rig` passed 29/29 on Shard and on vanilla with 2 stock clients.
- Deviations: `localeprint` uses the locale `en`, and a missing key gives `???key???` without the game bundle.
  19 of 19 one-line breaks fail a fixture (`scripts/breaks/w3.12-objectives.py`).
- Open: the campaign's sector objectives; no marker in `ObservationTest` (the default maps have none).
