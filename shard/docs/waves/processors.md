# Processors and the logic VM

Read before touching `logic/**`, `sim.blocks.logic`, `sim.logic`, `sim.ai.LogicAi`, world processors, logic sensing or
unit statements (`ucontrol`, `ubind`, `radar`, `spawn`, ...). Rows: W1.10, W2.9, W2.23. Their owned paths and WPs are in
[`history.md`](history.md). Index, ownership rules and live contracts: [`WAVES.md`](../../WAVES.md). Paths are relative
to `shard/`.

**Processors (W2.9 done: WP67; W2.23 done: WP69 and the world statements)**
- Done: the logic VM's scripts (`runScript`) and live state that continues mid-program (`LogicState`, `StateTest`);
  `LogicPart` per world, with links following buildings through `TileWatcher`; every processor, memory, message,
  switch, display and canvas block, with codecs; world processors on tiles, flags, rules and waves, with their Calls
  in vanilla's order; the sensor surface (`Building.sense`, `senseObject`, `setProp`) and the power sensors; review
  38's frozen `ConfigValue`s. `LogicOracleTest` 7/7 runs frame by frame against vanilla, then through a save.
- Done (W2.23):
  - `sim.ai.LogicAi`, with `ucontrol`'s commands and vanilla's 600-tick release back to the type's AI;
  - `ubind`, `uradar`, `radar` and `ulocate`;
  - `fetch`, `query`, `spawn`, `status`, `bullet`, `explosion`, `spawnwave`, `weathersense` and `weatherset`, with
    their Calls;
  - every unit sensor, and `setprop` on units.

  `UnitLogicOracleTest` is exact for 9 fixtures of 600–1,100 frames, through saves. All 19 breaks fail it.
- Open:
  - the payload statements: still counted no-ops, refused at map load. Closed by W5.7: `ucontrol payDrop`, `payTake`,
    `payEnter` and `setprop @payloadType` run (`UnitLogicOracleTest`: a mega carrying, units entering a conveyor, a
    router and a reconstructor, through saves); `runScript` has no strict mode, as nothing is refused;
  - `bullet`'s result is null, where vanilla's is a pooled object (VANILLA-QUIRKS Q137);
  - `syncVariable` counts ticks;
  - a non-rectangular tiled display's root may differ;
  - `LogicBudget`'s weights are uncalibrated.
- W3.7 moved `LogicUnits`' ore lookup and the item-transfer events onto `OreIndex` and `ItemTransfers`, and ported
  `ucontrol mine` (`UnitLogicOracleTest.aMonoMinesWhereItIsTold`). `LogicUnits.Relink` still runs after admission;
  relinking from `forked()`, as `FollowerPlans` does, would keep a copy exact when a snapshot replaces plans in its
  first tick.
