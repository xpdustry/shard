package com.xpdustry.shard.sim.world;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;

import java.util.*;

/**
 * What a new world is made from.
 *
 * @param content the pinned content epoch; every block's kind must be registered
 * @param width the grid width in tiles
 * @param height the grid height in tiles
 * @param seed the seed of the world's gameplay {@code Rand}
 * @param systems the phase handlers and parts to run
 * @param kinds the building kinds to bind content to
 * @param inboxCapacity how many inputs may wait between two ticks
 * @param journal the journal to record mutations in
 */
public record WorldConfig(ContentEpoch content, int width, int height, long seed, Systems systems, KindRegistry kinds,
                          int inboxCapacity, Journal journal){
    public static final int DEFAULT_INBOX_CAPACITY = 4096;

    public WorldConfig{
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(systems, "systems");
        Objects.requireNonNull(kinds, "kinds");
        Objects.requireNonNull(journal, "journal");
    }

    /** Built-in systems and kinds, the default inbox and an in-memory journal. */
    public static WorldConfig of(ContentEpoch content, int width, int height, long seed){
        return new WorldConfig(content, width, height, seed, Systems.builtIn(), KindRegistry.builtIn(),
            DEFAULT_INBOX_CAPACITY, new MemoryJournal());
    }

    public WorldConfig withSystems(Systems systems){
        return new WorldConfig(content, width, height, seed, systems, kinds, inboxCapacity, journal);
    }

    public WorldConfig withKinds(KindRegistry kinds){
        return new WorldConfig(content, width, height, seed, systems, kinds, inboxCapacity, journal);
    }

    public WorldConfig withInboxCapacity(int inboxCapacity){
        return new WorldConfig(content, width, height, seed, systems, kinds, inboxCapacity, journal);
    }
}
