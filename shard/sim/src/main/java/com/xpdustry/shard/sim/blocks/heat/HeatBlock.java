package com.xpdustry.shard.sim.blocks.heat;

/** Vanilla's {@code HeatBlock} (HeatBlock.java): a building that gives heat to the {@link HeatConsumer}s it touches. */
public interface HeatBlock{
    /** The heat it gives, split over its edge by {@link Heat#calculate}. */
    float heat();

    /** {@link #heat()} as a fraction of its maximum. */
    float heatFrac();
}
