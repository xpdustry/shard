package com.xpdustry.shard.sim.blocks.payload;

import com.xpdustry.shard.sim.world.*;

/** The payload blocks' own Calls, published where vanilla sends them. */
public sealed interface PayloadBlockEvent extends WorldEvent{
    /**
     * {@code NetServer.syncBuilding(build)}: {@code Call.blockSnapshot} of the building at {@code tile}
     * ({@code y * width + x}), after a payload conveyor dumped its payload (PayloadConveyor.java:212).
     */
    record Synced(int tile) implements PayloadBlockEvent{
    }
}
