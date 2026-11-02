# Defence, sandbox, projectors and support blocks

Read before touching `sim.blocks.defense`, `sim.blocks.sandbox`, `sim.blocks.units`, walls, doors, mines, projectors,
radar, the build tower, point defence, or legacy and campaign blocks. Rows: W3.5, W3.6, W4.7. Their owned paths and WPs are in
[`history.md`](history.md). Index, ownership rules and live contracts: [`WAVES.md`](../../WAVES.md). Paths are relative
to `shard/`.

**Defence and sandbox (W3.5 done: WP39, WP40)**
- Done: every wall (surge lightning, phase deflection and plastanium's laser absorption in a shared `WallHits`), the
  thruster, the shielded wall, doors and blast doors, and the shock mine; the target dummy, the power void (vanilla's
  plain `Building`), the legacy pads and factories, and the launch pad (with its `LaunchPayload`), landing pad and
  accelerator off the campaign. `DefenseOracleTest` is exact for 900–1,552 frames per fixture (11), most of them
  through a save; `LegacyBodiesOracleTest` reads every legacy body byte-exact. All 14 breaks fail.
- Open: the campaign's launches and imports. `LivePlacedOracleTest`'s unported blocks were the cargo unload point,
  the ground factory and the cargo loader: no unported block without a body was left. Closed: W4.1 ported the ground
  factory and W4.7 the loader and the unload point, and each swapped its block in the test.

**Unit cargo blocks (W4.7 done: WP70, second half)**
- The unit cargo loader and unload point (`sim.blocks.units`) are ported with `CargoAi` (`ai-paths.md`). The loader writes
  no unit until its first update after a load (Q249, kept). Its cap check is `Units.canCreate` (W4.1).

**Projectors and support turrets (W3.6 done: WP63, WP64)**
- Done: the mend, overdrive, force and regen projectors, the shockwave tower, radar, the base shield and the build
  tower (`sim.blocks.defense`); point defence and the tractor beam (`sim.blocks.turret`); the repair turret and tower
  (`sim.blocks.units`). Shared pieces:
  - `TeamBlocks.eachBlock`, in `indexer.eachBlock`'s order;
  - `Bullets.intersectQuad` over `BulletTree`, the bullet group's quadtree in vanilla's fill order, removed bullets
    included;
  - `ExplosionShields`: force fields absorb explosion waves and wreck crashes;
  - `RegenMends`, vanilla's static mend map, kept per world.

  `ProjectorOracleTest` (14 fixtures, 900–1,800 frames), `BuildTowerOracleTest` and `BulletTreeOracleTest` are exact
  against vanilla, with save chunks byte-equal. All 14 breaks fail. Radar's range follows vanilla (`RangedTest`).
- Deviations: the repair tower finds units in Shard's grid order, compared as a set (W2.8's known deviation);
  `fogControl` isn't modelled for radar (closed by W4.3: a radar's growing fog radius redraws its team's fog, `fog.md`); player-controlled build towers aren't ported.
- Open: no fixture reaches a bullet that survives a shockwave or a point-defence shot; the crash fixture uses flares
  weakened to 5 health.
