# Production, liquids, crafting and heat

Read before touching `sim.blocks.production`, `sim.blocks.liquid`, `sim.blocks.crafting` or `sim.blocks.heat`: drills,
pumps, conduits, tanks, crafters, separators, heat. Rows: W3.2, W3.3, W3.4. Their owned paths and WPs are in
[`history.md`](history.md). Index, ownership rules and live contracts: [`WAVES.md`](../../WAVES.md). Paths are relative
to `shard/`.

**Production (W3.3 done: WP35)**
- Done: the drills (mechanical to blast, plus the impact and eruption burst drills), the plasma bores, the cliff
  crushers, the pumps, the water extractor and the oil extractor, with their codecs and logic sensing.
  `ProductionOracleTest` is exact frame by frame against vanilla on three fixtures (600–900 frames), then 300 more after
  vanilla's save. `ProductionMapOracleTest` keeps Salt Flats' 234 drills exact for 900 frames. All 9 breaks fail.
  M0's fortress drill is now live.
- Open: `SingleBlockProducer` (no vanilla content uses it); no fixture runs a reinforced
  pump or a payload drill. The Salt Flats gate still removes the map's unported blocks and turns waves off.
  `PassthroughBuilding`'s solid-pump branch is dead code now (the W2.1 area may remove it).

**Liquids (W3.4 done: WP36)**
- Done: the liquid source and void; every conduit (plain, pulse, plated, reinforced); the liquid router, container
  and tank; the liquid junction; the bridge and phase conduits on W3.1's `ItemBridgeBuild`; the reinforced bridge
  conduit; `LiquidBlock`. `LiquidOracleTest` is exact for 600 frames per fixture, plus 120 after vanilla's save.
  It covers every kind in one line, mixed liquids reacting and burning, broken conduits leaking, and bridges
  relinking and losing power. `PuddleOracleTest` uses the real conduit. All 11 breaks fail.
- Open: `LiquidBlock` has no fixture (no v160.7 block uses it); the plated conduit's junction and bridge cases and the
  junction's -1 direction have none either; drawing state; payload pickup of bridges.

**Crafting and heat (W3.2 done: WP34, and W2.7's heater generator and flux reactor)**
- Done: `GenericCrafterBuild`, the shared crafter base, with the generic, attribute and heat crafters; the separator
  (seeded picks, seed saved); both incinerators; `sim.blocks.heat` (`Heat.calculate`, producers, redirectors and the
  router); the flux reactor. The heater generator and the thorium reactor are `HeatBlock`s, and heat turrets take
  `heatReq` from `Heat.calculate`. `CraftingOracleTest` (7 fixtures, 600–2,000 frames), `HeatOracleTest` (3) and
  `HeatTurretOracleTest` are exact frame by frame against vanilla, through saves, with synced buildings'
  snapshot bytes equal. All 20 breaks fail.
- Open: malign shares afflict's path but has no fixture;
  `ignoreLiquidFullness` and `dumpExtraLiquid=false` are gated only through overlay content.

Contract for liquids (`LiquidSource`, conduits, `Combat.Splash`): "Contracts for wave 3" in
[`contracts.md`](contracts.md).
