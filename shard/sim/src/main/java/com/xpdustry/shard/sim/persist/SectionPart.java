package com.xpdustry.shard.sim.persist;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.sim.world.*;

/**
 * A world part that stores its authoritative state as a checkpoint section of its own, in its own versioned format,
 * instead of as named fields: for a part that stores a compact description of itself and rebuilds the rest from it
 * and the world. The path service is one: it stores its field registry and its view of the world at the last flush,
 * and rebuilds the flowfields (ARCHITECTURE.md §4).
 * <ul>
 *   <li>{@link #write} runs at the tick boundary of the capture;</li>
 *   <li>{@link #read} runs on a fresh instance once the world's tables and objects are restored, before derived parts
 *   and {@code restored()} hooks, and must leave the part exactly as it was, derived state included.</li>
 * </ul>
 * Only a part that says it is {@link Persistence#AUTHORITATIVE} is written; one that says it is derived is rebuilt
 * like any other. The coverage check can't see into the format, so the part's own tests compare a restored part with
 * the original, array by array.
 */
public interface SectionPart extends WorldPart{
    /** The section's name: short, ASCII, and unique among the checkpoint's sections. */
    String section();

    void write(World world, Writes out);

    void read(World world, Reads in);
}
