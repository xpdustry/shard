package com.xpdustry.shard.sim.tile;

import com.xpdustry.shard.sim.world.*;

/**
 * Committed tile changes, published to observers after the tick (vanilla's {@code TileChangeEvent},
 * {@code TileFloorChangeEvent}, {@code TileOverlayChangeEvent} and {@code BuildTeamChangeEvent}). One event per tile,
 * where vanilla fires one; none while a map loads. Positions are {@code y * width + x}.
 */
public sealed interface TileEvent extends WorldEvent{
    long tick();

    int pos();

    Cause cause();

    /** A tile's block changed: {@code block} and {@code team} are the new ones. */
    record Changed(long tick, int pos, int block, int team, Cause cause) implements TileEvent{
    }

    record FloorChanged(long tick, int pos, int previous, int floor, Cause cause) implements TileEvent{
    }

    record OverlayChanged(long tick, int pos, int previous, int overlay, Cause cause) implements TileEvent{
    }

    /** A building changed teams; {@code pos} is its origin tile. */
    record TeamChanged(long tick, int pos, int previous, int team, Cause cause) implements TileEvent{
    }
}
