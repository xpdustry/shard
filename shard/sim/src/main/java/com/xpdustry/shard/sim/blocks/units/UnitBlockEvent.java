package com.xpdustry.shard.sim.blocks.units;

import com.xpdustry.shard.sim.world.*;

/** The unit blocks' own Calls, published where vanilla sends them. */
public sealed interface UnitBlockEvent extends WorldEvent{
    /**
     * {@code Call.unitBlockSpawn(tile)} (UnitBlock.java:23): the unit factory or reconstructor at {@code tile}
     * ({@code y * width + x}) dumped its unit onto the ground; clients clear its payload and progress.
     */
    record Spawned(int tile) implements UnitBlockEvent{
    }
}
