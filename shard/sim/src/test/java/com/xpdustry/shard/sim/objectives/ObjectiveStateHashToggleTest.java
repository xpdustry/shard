package com.xpdustry.shard.sim.objectives;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.toy.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The toggle proof for {@code HashTest.GOLDEN} (WAVES.md "Golden hash"): the golden moved only because every world now
 * has {@link ObjectiveState}, whose markers, stats and locales the toy world leaves empty, and the rules' typed
 * objectives hash as the raw {@code "[]"} they replaced. Without the part the scripted world hashes to main's golden
 * at d31ba0c1, and with it every other section is the same.
 */
class ObjectiveStateHashToggleTest{
    static final long MAINS_GOLDEN = 0xe74fd830d9f001c3L;

    static World churned(Systems systems){
        World world = new World(WorldConfig.of(ToyContent.INSTANCE, 48, 48, 42).withSystems(systems));
        world.part(WorldRules.class).rules.disableUnitCap = true;
        world.part(Chaos.class).enabled = true;
        for(int i = 0; i < 500; i++) world.tick();
        return world;
    }

    static Systems withoutObjectiveState(){
        var parts = new ArrayList<Class<? extends WorldPart>>();
        for(Class<? extends WorldPart> part : Registries.classes(WorldPart.class)) if(part != com.xpdustry.shard.sim.ai.command.CommandBatches.class && part != com.xpdustry.shard.sim.ai.team.TeamAis.class) if(part != ObjectiveState.class && part != com.xpdustry.shard.sim.fog.FogControl.class) parts.add(part);
        return Systems.of(Registries.instances(PhaseHandler.class), parts);
    }

    @Test
    void withoutTheStateTheGoldenIsMains(){
        assertEquals(MAINS_GOLDEN, com.xpdustry.shard.sim.spatial.SpatialHashes.before(churned(withoutObjectiveState())).value());
    }

    @Test
    void theStateAddsOnlyItsOwnSection(){
        WorldHash with = churned(com.xpdustry.shard.sim.ai.command.CommandBatchesHashToggleTest.builtInBefore()).hash(), without = churned(withoutObjectiveState()).hash();
        var expected = new ArrayList<>(without.sections());
        var actual = new ArrayList<>(with.sections());
        assertTrue(actual.removeIf(section -> section.name().equals(ObjectiveState.class.getName())), "the objective state's section");
        // W4.3's fog came later, left out of both sides ({@code FogHashToggleTest})
        actual.removeIf(section -> section.name().equals(com.xpdustry.shard.sim.fog.FogControl.class.getName()));
        assertEquals(expected, actual);
    }
}
