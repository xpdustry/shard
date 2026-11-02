package com.xpdustry.shard.sim.blocks.defense;

import com.xpdustry.shard.sim.world.*;

/**
 * What vanilla tells clients about walls and doors with a Call of their own, as domain events, published after the
 * local effects ({@code called = Loc.server}). A door's toggles are configs: {@code BuildingEvent.Configured}.
 * Positions are origin tiles, {@code y * width + x}.
 */
public sealed interface DefenseEvent extends WorldEvent{
    long tick();

    int pos();

    /** {@code Call.autoDoorToggle(tile, open)} (AutoDoor.java:47): an auto door opened or closed. */
    record AutoDoorToggled(long tick, int pos, boolean open) implements DefenseEvent{
    }
}
