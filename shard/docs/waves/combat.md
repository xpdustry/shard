# Combat: bullets, weapons, turrets, fires and puddles

Read before touching `sim.bullet`, `sim.combat`, `sim.blocks.turret`, `sim.fire` or `sim.puddle`: bullet kinds, damage,
weapons, targeting, turrets and block units, fire and puddle behaviour. Rows: W2.8, W2.16, W2.18, W4.3. Their owned paths and
WPs are in [`history.md`](history.md). Index, ownership rules and live contracts: [`WAVES.md`](../../WAVES.md). Paths
are relative to the repository root.

**Bullets and weapons (W2.8 done: WP41, WP43)**
- Done: the bullet core and the basic, artillery, bomb, flak, missile, laser bolt, liquid, explosion, empty and fire
  kinds; collisions, `Damage` and `Lightning`; weapons, mounts, shoot patterns, point defense, repair and build
  weapons; death shots (review 38); generator explosions through W2.7's seam; byte-exact `createBullet`. 1,000
  seeded bullets, a dagger-and-flare duel (1,800 frames) and a thorium meltdown (300 frames) match vanilla exactly.
- W2.18 ported the WP61 kinds: `WeaponOracleTest` is 149/149, and fire is back on in the combat fixtures.
- Open: explosion shields. `MineWeapon` and `PointDefenseBulletWeapon` aren't in the content pack: no v160.7 unit uses
  them (only `ClassMap`, for mods), so there is nothing to port (W5.7). Area damage
  finds units in grid order, so deaths in one blast can come in another order. Delete `WaveSpawner.damage` (W2.19).
- For stage 2: `world.bullets().types(world).get(def).create(...)` and `createNet`; `Predict.intercept`,
  `ShootPattern.of(fields).shoot(...)`; `Bullet.of`, `Bullets.intersectTree`; `Weapons.aim`, `controlWeapons`,
  `canShoot`, `isShooting`, `Weapon.of(unit, mount).range()`; `Damage.damage`, `dynamicExplosion`,
  `Targets.eachBlock`, `Hazards`, `StatusApply`.

**Turrets (W2.16 done: WP60)**
- Done:
  - Turret kinds: item, power, liquid, laser, continuous and continuous-liquid. Each has ammo, reload, cooling, charge,
    recoil, heat, delayed bullets, sensors and logic control. `sort:grouped` uses a `UnitClusters` world part.
  - Save and sync codecs; `readSync` keeps the reader's own aim and reload, as vanilla does.
  - Block units: one is created with each turret, and a player can take control of it.
  - Building targets in `sim.combat.Targets`, in vanilla's quadtree order. `ChunkTargets` and `BuildingTargets` call
    them.
  - Gates against vanilla, exact frame by frame:
    - `TurretOracleTest`: duels for ten turrets, plus a wave turret putting out a fire;
    - `M0TurretOracleTest`: duos fed by conveyors kill fortress's first wave over 15,214 frames;
    - `TurretSyncOracleTest`: sync bytes for one turret of each kind;
    - `TargetingOracleTest`: 6,000 queries.
- Quirk kept: a liquid turret keeps the `Fire` object as its target. Once the fire is out it aims where the fire
  burnt, then at (0, 0) once the pool resets the object. `Fire.object` and `Fires.objectPosition` model it.
- Open:
  - Block units don't pass on damage, death or team, and no oracle covers a player controlling a turret. Closed by
    W5.7: `Unit.damage(amount, withEffect)`, `killed()` and `team(int)` pass on to the turret, and a player moves a
    controlled turret to its team; `BlockUnitOracleTest` (700 frames through a save) and `shard-turret.rig` (L5). W5.7
    also makes the block unit at the turret's construction, as vanilla does, so a client's kind-1 reference is a pure
    read (W5.5's F3, `BlockUnitHashFindingTest`). Deviation: vanilla's block unit is made while the turret is still
    derelict, so on wave or attack maps without PvP its controller is GroundAI; Shard's is always a CommandAI. Same
    draws; only the `lastCommand` a player keeps on taking a turret can differ.
  - `PlayerSink` doesn't read kind-1 player units from saves.
  - No test has construction finishing a turret.
  - The fog radius refresh isn't modelled. Closed by W4.3: `Building.fogRadius()` and `TurretBuild.lastRangeChange`
    redraw a turret's team's fog (`fog.md`); `fogRadius()` calls `hasAmmo()` like vanilla's (Q231).
- For stage 3:
  - `TurretBuild` gives `target`/`targetFire`, `hasTarget`, `peekAmmo`/`hasAmmo`, `range`, `isShooting` and
    `blockUnit`.
  - Sorting: `Sorts.of(world, "sort:…")`. Block units: `BlockUnits.at(world, pos)` and `canControl`.

**Fires, puddles and special bullets (W2.18 done: WP61)**
- Done: `sim.fire` (fires burn, spread, throw fireballs, damage; flammability, oxygen and rain) and `sim.puddle`
  (deposit, spread, evaporation, wet units, liquid reactions, oil igniting), pooled with vanilla's `reset()` draws,
  synced (`FireLayout`, `PuddleLayout`) and saved (`SavedHazards`); the WP61 kinds (laser, continuous, continuous
  laser and flame, sap, rail, shrapnel, EMP, point laser, lightning, space liquid) with `Lasers`; `Hazards` filled.
  `FireOracleTest`, `PuddleOracleTest`, `BeamOracleTest` and `HazardSaveOracleTest` are exact for 600–900 frames.
- Open: Interceptor, Multi and Point bullets (no content in the pack); `ContinuousFlameBulletType` beyond
  `interp: slope`; `CellLiquid` (neoplasm) unchecked; conduits aren't ported, so the puddle fixture binds a test-only
  conduit kind. Entity pools are per world (ARCHITECTURE §1).
- For stage 3: `Fires.create/extinguish/has/get/flammability`, `Puddles.deposit/get/hasLiquid`, `Hazards.of(world)`,
  `Lasers` (`collideLine`, `collideLaser`, `findPierceLength`, …); oracles call `HazardPools.empty()` after a load.
