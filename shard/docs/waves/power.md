# Power

Read before touching `sim.blocks.power`, power graphs, generators, reactors, or anything that reads a building's
`efficiency`. Rows: W2.7. Their owned paths and WPs are in [`history.md`](history.md). Index, ownership rules and live
contracts: [`WAVES.md`](../../WAVES.md). Paths are relative to the repository root.

**Power (W2.7 done: WP37, WP38)**
- Done: graphs and their updater group in vanilla's order, merges and splits through the proximity callbacks, reflow
  on `changeTeam`; nodes, beam nodes, diodes, batteries, illuminators; every consume, solar, thermal and turbine
  generator, the thorium reactor and the impact reactor, compared frame by frame (`PowerOracleTest`,
  `GeneratorOracleTest`); graphs and ids after loading 32 maps (`PowerMapsOracleTest`); `Buildings.tree(team)`
  (Arc's QuadTree) and `Buildings.tileChanges()`. Vanilla's `SolidPump.onProximityUpdate` re-merge quirk is kept.
- Open: explosions do nothing until W2.8 fills `GeneratorExplosions` (damage, fires, fireballs) and the base
  building explosion; the power void, node plan configs and
  placement-validity checks aren't ported; power sensing goes to W2.9; `Tile.reflowPower` belongs with
  `Call.setTeams` (W2.12). Payloads need a graph without an updater, `afterPickedUp` and the re-init
  (`BeamNode.pickedUp()` exists). The production row keeps the `SolidPump` quirk (never `noSleep`).
- For stage 2: a power turret declares its `ConsumePower`, and `efficiency` already includes it. Construction calls
  `Buildings.setBlock(...)` then `building.placed()`, as `constructFinish` does, which is what autolinks nodes.
