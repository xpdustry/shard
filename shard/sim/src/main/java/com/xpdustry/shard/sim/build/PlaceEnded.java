package com.xpdustry.shard.sim.build;

import com.xpdustry.shard.sim.unit.*;

/**
 * A kind whose block overrides {@code Block.placeEnded(tile, builder, rotation, config)}: construction calls it on the
 * building it just finished, after the config and {@code overwrote}, before the rebuild plans it covers are dropped
 * (ConstructBlock.java:118). A turret turns its aim to the block's rotation here (Turret.java:276).
 */
public interface PlaceEnded{
    /** {@code builder} is the unit credited with the build, or null. */
    void placeEnded(Unit builder, int rotation, Object config);
}
