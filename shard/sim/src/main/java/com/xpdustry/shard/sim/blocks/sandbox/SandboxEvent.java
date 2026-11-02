package com.xpdustry.shard.sim.blocks.sandbox;

import com.xpdustry.shard.sim.world.*;

/**
 * What vanilla tells clients about sandbox and campaign blocks with a Call of their own, as domain events, published
 * after the local effects ({@code called = Loc.server}). Positions are origin tiles, {@code y * width + x}.
 */
public sealed interface SandboxEvent extends WorldEvent{
    long tick();

    int pos();

    /** {@code Call.unitTetherBlockSpawned(tile, id)} (UnitCargoLoader.java:79): a target dummy made its unit. */
    record TetherSpawned(long tick, int pos, int unit) implements SandboxEvent{
    }

    /** {@code Call.landingPadLanded(tile)} (LandingPad.java:131): a landing pad's pod is on its way. */
    record PadLanded(long tick, int pos) implements SandboxEvent{
    }
}
