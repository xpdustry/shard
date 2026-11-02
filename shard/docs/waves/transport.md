# Transport: conveyors, ducts, bridges, the mass driver

Read before touching `sim.blocks.transport` or `sim.blocks.distribution`: conveyors, ducts, junctions, sorters, gates,
unloaders, item bridges, the mass driver. Rows: W2.6, W3.1. Their owned paths and WPs are in [`history.md`](history.md).
Index, ownership rules and live contracts: [`WAVES.md`](../../WAVES.md). Paths are relative to the repository root.

**Transport (W2.6 done: WP31, WP32, ducts; W3.1 done: WP33)**
- Done: every conveyor, junction, sorter, gate, unloader, duct, the duct unloader (`DirectionalUnloader`, through a save
  since W5.7) and the surge router, exact per frame
  (`ConveyorOracleTest`, `DistributionOracleTest`, `DuctOracleTest`); read-time `loaded()` updates; vanilla's blend
  table (`BlendingOracleTest`); units on belts through `Unit.impulse`; per-world unloader fairness (`ContainerStats`).
- Open: conveyor and duct logic sensing (`sense`, `senseObject`,
  `setProp`, after W2.9's sensor surface); payload drops;
  unloaders see every container as unlinked until W2.12 exposes `linkedCore` and `canUnload`.
- Done (W3.1):
  - the phase conveyor (vanilla's powered `ItemBridge`) and the bridge conveyor (`BufferedItemBridge`), on the shared
    `ItemBridgeBuild` base and `ItemBridgeBodies` codec;
  - the mass driver, with its state machine, its arrival as a timer-task record, and `MassDriverBolt`. Each bolt's
    payload lives in the `DriverBolts` world part, by bullet id.

  `BridgeOracleTest` is exact for 600 frames plus 300 after vanilla's save. It covers chains of 2–4 links both ways,
  relinks, a broken link, power loss, and a world processor's `setblock` and `control`. `MassDriverOracleTest` is exact
  for 1,800 frames plus 600, with three drivers relinking and their `writeSync` bytes equal to vanilla's.
  `BridgeConfigServerTest` forwards configs once and never back to the sender. All 13 breaks fail.
- Open from W3.1: bolts hitting enemy units and off-course bolts have no fixture; payload pickup and drop; flow
  statistics (`undoFlow`). `DirectionBridge` is W2.6's `DuctBridge`, and v160.7 has no `DuctJunction`.
- For stage 2: a turret fed by conveyors only needs `acceptItem`/`handleItem`. Anything that builds these blocks
  from saved bytes (payload reads) calls their `loaded()`, as the codecs do.

Contract for item bridges (`ItemBridgeBuild`, `ItemBridgeBodies`): "Contracts for wave 3" in
[`contracts.md`](contracts.md).
