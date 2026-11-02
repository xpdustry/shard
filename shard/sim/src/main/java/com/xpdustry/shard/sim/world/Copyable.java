package com.xpdustry.shard.sim.world;

/**
 * A world object that other world objects may reference: buildings, units, fires, power graphs. A fork copies it in
 * two steps through {@link CopyContext}, so references between such objects, cycles included, map to the copies
 * without recursion.
 *
 * <p>Objects owned by exactly one other object (a unit's mounts, a building's modules) don't need this: their owner
 * copies them directly, with a {@code copy(CopyContext)} method by convention.
 */
public interface Copyable{
    /** A new, empty instance of exactly this class. {@link #copyTo} fills it. */
    Copyable blank();

    /**
     * Copies this object's state into {@code into}, a {@link #blank()} of this class. Map every reference to another
     * {@code Copyable} through {@link CopyContext#map(Copyable)}, copy owned objects, clone arrays.
     */
    void copyTo(Copyable into, CopyContext context);
}
