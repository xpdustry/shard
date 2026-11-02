# Map loading and hosting

Read before touching `MapLoader`, `compat.v160.msav`, `compat.v160.world`, `compat.v160.filters`, the L3 map gate,
live-placed unported blocks (`FreshBodies`), or the clock a loaded save continues from. Rows: W1.7, W2.3, W2.12
(`Logic.play`), W2.19, W4.3. Their owned paths and WPs are in [`history.md`](history.md). Index, ownership rules and live
contracts: [`WAVES.md`](../../WAVES.md). Paths are relative to the repository root.

**Leftovers (W2.19 done)**
- Done: live-placed unported blocks stream and save with vanilla's fresh bodies (`FreshBodies`, 282 blocks;
  `LivePlacedOracleTest`); a loaded save's clock continues (`Timers.continueFrom`); payload buildings with base
  versions 0/1, so saves v108–v135 load whole; units keep their saved class; static fog; L3 on all 112 hostable maps;
  `WorldStreamOracleTest`'s golden at load with no accepted differences; `Waves.defaults`; conveyor and duct sensing;
  the duct unloader; the heater generator; the session viewer's view size; build-logic targets 25.
- Open: the power void (its kind key `block` is shared with
  `legacy-block`); static fog doesn't grow during play (closed by W4.3, `fog.md`); units carrying buildings keep them as bytes. Deviation: a
  loaded save's clock is "a server that played only this game".

**Map hosting (new WP; needed for M1)**
- Done: filters, v1–3 chunks and `nocores` (W2.3); `lastDisabler`, `visibleFlags` and the creation draws (W2.1).
  The filter oracle refused 3 of 114 bundled maps: one with old-revision `PayloadUnit`s (with W2.5), and
  `hidden/242` and `littoralShipyard` for their data patches, which W5.4 brought in (below).
- Maps left out of L3, each differing only by its gap (`leftOutMapsDifferOnlyByTheirGap`). W2.6 brought in the six
  conveyor-read maps (hidden/19, 47, 197, 259, 263, 265) and hidden/191. Still out:
  - static fog: in (W5.4). `WorldStreamOracleTest` already ran every hosted map and compares custom chunks by name;
    `StaticFogMapsOracleTest` compares their bytes with vanilla's on every hosted map, at load and, for the 15 Erekir
    maps with `static-fog-data`, hosted with `logic.play()` too;
  - uncounted draws from old-revision `PayloadUnit`s (hidden/16, 30), with W2.5; also entity-region
    generator draws (W2.2's area).
- `LogicFilter` scripts run (W2.9); one that reaches a statement Shard doesn't run yet still refuses the map.
- `Logic.play` (W2.12 done): `MapLoader.play` matches vanilla on all 18 default maps (`PlayOracleTest`); saved
  units come to life with vanilla's ids and draws (`SavedUnitsOracleTest`), and world streams hold live units and
  weather. W2.4h calls `MapLoader.play` after `loadMap`. Open: payload buildings with base versions 0 and 1
  (`BuildingBase.skip`), without which saves v108–v135 can't load whole; `MsavWriter` should write through
  `WorldProjection.entities`, not the loaded units' old bytes; `EntityProjector` should keep a carried unit's saved
  class; fog filtering of streamed units (done by W4.3 for live units; units kept as bytes aren't filtered); saved fires and puddles stream before weather; plan configs keep the
  file's content ids; carried buildings inside units stay as bytes.

**Map data patches and assets (W5.4 done)**
- Done: a map's data assets are read as `readDataPatches` reads them (`content.patch.DataAssets`, v11–v13); its patches
  are applied by `content.patch.DataPatcher` (vanilla's `assign`/`resolve` over baked structs, `requiredPlanets`,
  `afterPatch`) into the world's own `Epoch` (`Epoch.patched`), which keeps the base's ids and names, so other worlds
  and the shared epoch never see it; the stream and saves write the map's section back as vanilla does
  (`MapState.patches`); external assets keep their bytes (`MapState.assets`) for `AssetStreams`. Gates:
  `MapPatchesOracleTest`, `PatchedMapsOracleTest` (600 frames exact, both maps and a patched fixture with a sprite),
  `ContentGatesTest.mapPatchesApplyAsVanillas`, `PatchedWorldsServerTest`, `shard-patches.rig`.
- Refused, with a message: content assets, patches with `type` objects (new bullets and effects), consumers,
  controllers, fields Shard's schema lacks, bullet edits (their `afterPatch` range), unit factories', reconstructors',
  assemblers' and build turrets' `afterPatch`, external assets stored by hash only, map patches over non-vanilla content.
- Done (W6.2): the join's asset handshake (`server.host.AssetHandshake`, `HostedWorld.requestAssets`/`requestWorld`,
  `ConnectFlow`): a join or a map change of a world with external assets gets the `AssetRequirementStream` (stream 4)
  first, `requestAssets` is honoured once per load (empty: the world at once; else the `AssetStream`, stream 5, written on
  `io-cpu`), and `requestWorld` then sends the world; anything out of order is ignored, as vanilla's flags ignore it;
  `/sync` sends the world alone. Bounds: one asset stream per load, each asset once, at most the map's own; the load
  timeout covers the handshake. The streams are vanilla's byte for byte (`PatchedMapsOracleTest`), the order and bounds
  hold on a live server (`PatchedWorldsServerTest`), and a stock client gets the sprite (`shard-patches.rig`). Streams
  beside a load go through `Session.streamAside` (they earn no `connectConfirm`).
- Done (W6.2): `WorldSync` binds its snapshots and Calls to the attached world's content, and `WorldSends` projects
  players and entities with it, so a carried passthrough building of a patched world projects
  (`PatchedWorldSyncTest`, `PatchedWorldProjectionTest`).
- Done (W6.2): a hosted world's config has the map's own content (`Host.prepare`: `MapLoader.contentOf`, rebuilt from the
  file on every start), so wakes, recoveries and resumes after a restart restore with its hash
  (`PatchedWorldsServerTest`'s hibernation, `PatchedCheckpointTest`: 600 ticks with equal hashes from a restore on a
  rebuilt epoch). `ContentSource.remember` lets the in-process readers (replay, crash bundles) resolve a patched hash:
  patched epochs only, keyed by hash, held weakly (`RememberedContentTest`); a hash nothing remembers fails naming it.
- Done (W6.2, review 45): patched-map play after a save and reload. `PatchedMapsOracleTest`'s fixture is saved by vanilla
  with its assets embedded (`save <slot> true`) and loaded by both; the patch text, the sprite bytes, the patched content
  and the data patch section are kept, and the run stays exact for 300 more frames (`ProductionRun.reload`).
- Done (W6.2): Shard's own saves (W6.4's console `save`, `WorldSends.save`) embed a map's external assets, as
  `save <slot> true` does, and load back with the sprite and the patched content (`PatchedWorldsServerTest`). Deviation:
  vanilla's plain `save` names them by hash, which its asset cache serves; Shard has no cache.
- Open: a vanilla save with external assets by hash (plain `save <slot>`) is refused, with a message. A crash bundle of a patched world replays only in a process
  that loaded the map (the checkpoint doesn't carry the patches).

**Time.time and timers after a load (a Units-area item; W2.19 owns the model)**
- A model of vanilla's `Time.time` for AI timers (they use the tick count) and building `timer()`s. Vanilla's
  `Interval`s count against a global clock that keeps running across a load, so a freshly loaded building's timers
  fire on its first frame (W2.9 saw a thorium reactor burn fuel at once); a new Shard world's don't.
