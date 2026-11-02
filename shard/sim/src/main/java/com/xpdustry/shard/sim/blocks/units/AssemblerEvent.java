package com.xpdustry.shard.sim.blocks.units;

import com.xpdustry.shard.sim.world.*;

/**
 * The unit assembler's own Calls, as domain events, published after their local effects ({@code called = Loc.server}).
 * Positions are origin tiles, {@code y * width + x}.
 */
public sealed interface AssemblerEvent extends WorldEvent{
    long tick();

    int pos();

    /** {@code Call.assemblerDroneSpawned(tile, id)} (UnitAssembler.java:491): the assembler made drone {@code unit}. */
    record DroneSpawned(long tick, int pos, int unit) implements AssemblerEvent{
    }

    /**
     * {@code Call.assemblerUnitSpawned(tile)} (UnitAssembler.java:532): the assembler finished its unit; on clients the
     * handler makes it too, with the same consumption and draws.
     */
    record UnitSpawned(long tick, int pos) implements AssemblerEvent{
    }
}
