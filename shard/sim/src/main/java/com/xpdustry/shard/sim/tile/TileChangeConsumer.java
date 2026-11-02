package com.xpdustry.shard.sim.tile;

/** Receives the tick's changed tiles from {@link TileGrid#changes(TileChangeConsumer)}, one call per position. */
@FunctionalInterface
public interface TileChangeConsumer{
    /**
     * @param pos the changed tile, {@code y * width + x}
     * @param before its {@link TileState} before its first change since the previous tick began; the current state
     *     may be equal (a tile changed and changed back), and may have moved on since the tick began
     */
    void changed(int pos, long before);
}
