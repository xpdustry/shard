# Fog

Read before touching `sim.fog`, `Building.fogRadius`, the `TileWatcher` hooks, `StaticFog`, fog filtering of entity
snapshots, or the team assignment on join. Rows: W4.3. Its owned paths and WPs are in [`history.md`](history.md). Index,
ownership rules and live contracts: [`WAVES.md`](../../WAVES.md). Real fog (withholding buildings) is specified in
[`../architecture/fog.md`](../architecture/fog.md). Paths are relative to the repository root.

**Fog (W4.3 done: WP73)**
- Done:
  - `sim.fog.FogControl` and `FogUpdate`: each team's discovered and visible tiles, with vanilla's statics (`inFogTo`,
    `isDiscovered`, `visibleTile`, `discoveredTile`, `forceUpdate`, `unitDestroyed`). Fog hides units from targeting and
    from other teams' entity snapshots, and `Build` refuses placement on undiscovered tiles. It is checkpointed;
  - static fog grows during play: `TileWatcher` has three default hooks (`tileChanging`, `tileChanged`, `loaded(World)`),
    which `Buildings` calls, with `Buildings.teamBuildingCount`, `flaggedCount` and `flaggedAt` (copy-free counts);
  - `Building.fogRadius()`, with `TurretBuild.lastRangeChange` and radar's growing radius (`combat.md`, `defense.md`);
  - `Build`: static-fog placement, the place sound only in sight, a refresh when a derelict is repaired;
  - cosmetic fog only: buildings stay visible to every client, and real fog stays off.
- Gates: L1 `FogOracleTest`, 2 fixtures exact for 1,500 frames through a checkpoint, against vanilla with its fog
  threads pinned to Shard's schedule (`VanillaFog`): parity under that schedule; 12 of 12 breaks fail
  (`scripts/breaks/w4.3-fog.py`); L5 `shard-fog.rig` 24/24; `FogHashToggleTest` proves the golden's move from
  `0xc5c5f118553c790a` to `0xc6b64a5e94e08c4e`. Added by W4.10 (review 43):
  - `FogOutcomeOracleTest`: vanilla's fog unmodified (its two threads and the 40 ms wall-clock gate), frames paced at
    60 Hz. Two radars and five units that walk and stop, no targeting; after 900 frames both sides run on until vanilla's
    threads are idle (settled at about frame 906). Then, at tolerance 0: each team's visible and discovered tiles, the
    `static-fog-data` chunk, each team's snapshots and hidden ids byte for byte, and the units in each team's world
    stream. Exact holds because discovered tiles come from a queue of every event, and a settled scene's visible tiles
    are its last redraw. It proves the outcome, not the frames in between;
  - `FogSaveOracleTest`: a vanilla save made mid-redraw (the first frame from 300 with a team's `dynamicUpdated` set),
    loaded by vanilla (`SaveIO.load`) and by Shard (`MapLoader`). The static chunk matches as loaded, then fog, units,
    snapshots and streams are exact for 600 pinned frames;
  - `TurretRangeHashToggleTest`: in a turret world without fog, `lastRangeChange` stays 0, and with its one `Hasher.f`
    stripped from the bytecode the world hashes as at `9ae67530` (`0x3aff6a38f39f443c`). So the move is exactly that
    constant word, in the `buildings` section. Under fog, graphite moves the field to 16, and changing only the field
    moves the hash;
  - `scripts/breaks/w4.10-review43.py`: one break per new test (see its log).
- Cost: with fog off, one cached rules-field read per tick, no grids, and `inFogTo` and the tile hooks read the rules
  first (`inFogTo` was called 39 times over 15,196 frames on M0's fortress). A fog map holds about 3 × w × h / 8 bytes per
  team (94 KB a team at 500 × 500).
- Deviation: the redraw gate is 3 ticks, drawn synchronously in `TEAMS`, where vanilla uses 40 ms of wall clock on
  threads; the oracle pins vanilla to it (`VanillaFog`). It is in `docs/architecture/tier-exceptions.md`.
- Quirks: Q226–Q232 (`quirks/fog.md`), Q233 (team assignment), and a note on Q103. `TurretBuild.lastRangeChange` is
  hashed as 0 without fog, so a turret world's hash moves by that one constant word. It is an intended format change
  (the lead's ruling, review 43: it is stored state). No golden holds a turret, so `HashTest.GOLDEN` didn't move for it;
  `TurretRangeHashToggleTest` proves the move.
- Closed by W4.3: `maps.md` "static fog doesn't grow during play"; `sync.md` "units in the world stream aren't
  fog-filtered" (live units); `combat.md`'s fog radius refresh; `defense.md`'s radar `fogControl`.
- Gaps:
  - real fog is off, and `Audience.At` still means everyone;
  - saved units kept as raw bytes aren't fog-filtered in streams;
  - covered by W5.8 (`FogGapsOracleTest`, `FogAiTeamOracleTest`, frame by frame with the static-fog chunk and a
    checkpoint restore): `hitSize` over 16 (a scepter seen by its edges), `isOnlyAI` teams, `validPlace` refusing
    undiscovered tiles (every tile, every 50 frames), and a radar removed;
  - `StaticFog.afterLoad` remains for worlds without the fog part.
- Open for WP83 (PvP): review `HostInputs.team` (vanilla's `assignTeam`, Q233). Left: a draw-order oracle, vanilla's null
  when no team qualifies (Shard falls back to its default team), and the pluggable assigner hook. Closed by W5.3: the pluggable `TeamAssigner`, vanilla's null and reloads ([`pvp.md`](pvp.md)).
