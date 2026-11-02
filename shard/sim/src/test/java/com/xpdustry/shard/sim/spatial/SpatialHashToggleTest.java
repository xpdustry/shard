package com.xpdustry.shard.sim.spatial;

import com.xpdustry.shard.sim.toy.*;
import com.xpdustry.shard.sim.world.*;

import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The toggle proof for {@code HashTest.GOLDEN} (WAVES.md "Golden hash"): W6.1 added each unit grid member's hitbox at
 * the fill to the grids' state, where vanilla's team trees placed it, and so to {@link SpatialIndex}'s hash section.
 * The toy world plays as before (it fires no bullets, and nothing else in it reads {@code nearbyEnemies}): with the
 * fill hitboxes left out of that section, the scripted world hashes to main's golden, and every other section is
 * untouched.
 */
class SpatialHashToggleTest{
    /** {@code HashTest.GOLDEN} at the wave 4c base, main at {@code afbef8a5}. */
    static final long MAINS_GOLDEN = 0xb50bf72f98bbb1e3L;
    static final String SECTION = SpatialHashes.SECTION;

    static WorldHash before(World world){
        return SpatialHashes.before(world);
    }

    @Test
    void withoutTheFillHitboxesTheGoldenIsMains(){
        World world = Worlds.churned(42, 500);
        long value = before(world).value();
        assertEquals(MAINS_GOLDEN, value, String.format("0x%016xL", value));
        assertNotEquals(MAINS_GOLDEN, world.hash().value(), "the fill hitboxes are hashed");
    }

    @Test
    void theFillHitboxesChangeOnlyTheIndexSection(){
        World world = Worlds.churned(42, 500);
        WorldHash with = world.hash(), without = before(world);
        assertEquals(SECTION, without.firstDifference(with).orElseThrow());
        var rest = new ArrayList<>(with.sections());
        rest.removeIf(section -> section.name().equals(SECTION));
        var restBefore = new ArrayList<>(without.sections());
        restBefore.removeIf(section -> section.name().equals(SECTION));
        assertEquals(restBefore, rest);
    }

    @Test
    void anUnrelatedChangeFailsTheProof(){
        World world = Worlds.churned(42, 500);
        world.rand().nextInt();
        assertNotEquals(MAINS_GOLDEN, before(world).value());
    }
}
