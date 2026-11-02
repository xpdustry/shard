package com.xpdustry.shard.sim.path;

import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static com.xpdustry.shard.sim.path.PathWorlds.*;
import static org.junit.jupiter.api.Assertions.*;

/** {@link WorldRules#pushPathRules}: the rules model's copy reaches the path service at its next flush, and only then. */
class PathRulesPushTest{
    @Test
    void pushedRulesApplyAtTheNextFlush(){
        World world = world(20, 20, 1, List.of(WorldRules.class));
        for(int pos = 0; pos < world.tiles().size(); pos++) world.tiles().floor(pos, id("stone"));
        PathService paths = paths(world);
        paths.invalidateAll();
        paths.rules(PathRules.DEFAULT.withThreatPaths(true).withRtsPopBudget(5));
        world.tick();

        Rules rules = world.part(WorldRules.class).rules;
        rules.waves = true;
        rules.pvp = true;
        rules.attackMode = true;
        rules.waveTeam = 3;
        rules.defaultTeam = 4;
        rules.coreCapture = true;
        rules.randomWaveAI = true;
        rules.limitMapArea = true;
        rules.limitX = 1;
        rules.limitY = 2;
        rules.limitWidth = 10;
        rules.limitHeight = 12;
        world.tick();
        assertEquals(PathRules.DEFAULT.withThreatPaths(true).withRtsPopBudget(5), paths.rules(), "nothing pushes per tick");

        world.part(WorldRules.class).pushPathRules(world, 7);
        assertFalse(paths.rules().pvp(), "pending until the flush");
        world.tick();
        assertEquals(new PathRules(true, true, true, 3, 4, 7, true, true, true, 1, 2, 10, 12, false, true, 5), paths.rules(),
            "vanilla's rules and the wave from the model, Shard's layers kept");
    }
}
