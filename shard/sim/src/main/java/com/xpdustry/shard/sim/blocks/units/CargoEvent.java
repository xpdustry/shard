package com.xpdustry.shard.sim.blocks.units;

import com.xpdustry.shard.sim.world.*;

/**
 * What vanilla tells clients about the cargo blocks with a Call of their own, as domain events, published after the
 * local effects ({@code called = Loc.server}). Positions are origin tiles, {@code y * width + x}.
 */
public sealed interface CargoEvent extends WorldEvent{
    long tick();

    int pos();

    /** {@code Call.unitTetherBlockSpawned(tile, id)} (UnitCargoLoader.java:120): a cargo loader made its unit. */
    record TetherSpawned(long tick, int pos, int unit) implements CargoEvent{
    }
}
