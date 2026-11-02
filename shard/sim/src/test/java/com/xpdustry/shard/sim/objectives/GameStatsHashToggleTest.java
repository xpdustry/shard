package com.xpdustry.shard.sim.objectives;

import com.xpdustry.shard.sim.blocks.turret.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * W5.7's toggle proof for the destroyed-building stats ({@code ObjectiveState.destroying}): in
 * {@link TurretRangeFixture}'s world the duo destroys crux walls, and the only stats that move are
 * {@code destroyedBlockCount}'s (no default-team building dies, nothing is built). So putting back zeroed stats, as
 * {@code TurretRangeHashToggleTest} does, removes exactly W5.7's input. {@code HashTest.GOLDEN}'s toy world destroys
 * no building, so the golden didn't move.
 */
class GameStatsHashToggleTest{
    @Test
    void onlyTheDestroyedBlocksCount(){
        World world = TurretRangeFixture.world(TurretRangeFixture.before(), false);
        for(int tick = 0; tick < TurretRangeFixture.TICKS; tick++) TurretRangeFixture.tick(world);
        GameStats stats = ObjectiveState.of(world).stats;
        assertFalse(stats.destroyedBlockCount.isEmpty(), "the duo destroyed crux walls");
        var zeroed = stats.copy();
        zeroed.destroyedBlockCount = new TreeMap<>();
        var expected = new Hasher();
        new GameStats().hash(expected);
        var actual = new Hasher();
        zeroed.hash(actual);
        assertEquals(expected.value(), actual.value(), "every other stat is still zero");
    }
}
