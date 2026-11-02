package com.xpdustry.shard.sim.blocks.heat;

/** Vanilla's {@code HeatConsumer} (HeatConsumer.java): a building that sums its neighbours' heat with {@link Heat#calculate}. */
public interface HeatConsumer{
    /** The heat that came in through each side last time, by direction; {@link Heat#calculate} fills it. */
    float[] sideHeat();

    /** The heat it needs for full efficiency. */
    float heatRequirement();
}
