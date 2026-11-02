package com.xpdustry.shard.sim.building.consume;

import com.xpdustry.shard.content.*;

/**
 * The payloads a building holds, by content (vanilla {@code PayloadSeq}): what payload consumers read and take.
 * Payload kinds (WP39) implement it and return it from {@code Building.payloads()}.
 */
public interface PayloadStock{
    /** How many of the block or unit type {@code id} are held. */
    int get(ContentType type, int id);

    /** Removes up to {@code amount} of them. */
    void remove(ContentType type, int id, int amount);

    /** {@code contains(content, amount)}. */
    default boolean contains(ContentType type, int id, int amount){
        return get(type, id) >= amount;
    }

    /** A block or unit type and an amount ({@code PayloadStack}). */
    record Stack(ContentType type, int id, int amount){
    }
}
