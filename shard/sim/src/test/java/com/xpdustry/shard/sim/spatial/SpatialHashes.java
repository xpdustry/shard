package com.xpdustry.shard.sim.spatial;

import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * For toggle proofs pinned to a golden from before W6.1: W6.1 hashes each unit grid member's hitbox at the fill in
 * {@link SpatialIndex}'s section. A world's hash as it was then has that section without them
 * ({@code SpatialHashToggleTest}).
 */
public final class SpatialHashes{
    public static final String SECTION = SpatialIndex.class.getName();

    private SpatialHashes(){
    }

    /** {@code world}'s hash with the index's section as it was before W6.1. */
    public static WorldHash before(World world){
        return before(world, world.hash());
    }

    /** {@code hash}, a hash of {@code world}, with the index's section as it was before W6.1. */
    public static WorldHash before(World world, WorldHash hash){
        var hasher = new Hasher();
        SpatialIndex.of(world).hash(hasher, false);
        var sections = new ArrayList<WorldHash.Section>();
        for(WorldHash.Section section : hash.sections()){
            sections.add(section.name().equals(SECTION) ? new WorldHash.Section(SECTION, hasher.value()) : section);
        }
        return WorldHash.of(sections);
    }
}
