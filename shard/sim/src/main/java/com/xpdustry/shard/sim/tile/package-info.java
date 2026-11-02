/**
 * The tile grid (vanilla {@code Tile}, {@code Tiles}, {@code Edges}): {@link com.xpdustry.shard.sim.tile.TileGrid}'s
 * flat columns, predicate bitsets and change list, the {@link com.xpdustry.shard.sim.tile.Tile} view, multiblock
 * geometry ({@link com.xpdustry.shard.sim.tile.Footprint}), the per-content
 * {@link com.xpdustry.shard.sim.tile.BlockTable} (read from the epoch's block definitions), tile events and the packed
 * {@link com.xpdustry.shard.sim.tile.TileState} that change readers get. The placement primitives that change tiles
 * live in {@link com.xpdustry.shard.sim.building.Buildings}.
 *
 * <p><b>Reading tile changes.</b> A subsystem that keeps its own view of the tiles (paths, fog, spatial indexes,
 * logic) reads the tick's change list with {@code TileGrid.changes(consumer)}, in its own phase. The world starts the
 * list before {@code COMMANDS} and clears it after the last phase. It holds every tile changed since the previous tick
 * began, once, with its state from before; so whatever phase a reader runs in, it sees each change exactly once, at
 * most one tick late. The list doesn't move during the tick: changes made meanwhile go to the next one. Readers only
 * iterate it; none drains or clears it, since they all share it. A reader that must see a change in the same tick
 * (processor links that vanish) hooks the placement primitives instead.
 *
 * <p>Owner: WP11, with WP27 for environment blocks (floors, ores, walls).
 */
package com.xpdustry.shard.sim.tile;
