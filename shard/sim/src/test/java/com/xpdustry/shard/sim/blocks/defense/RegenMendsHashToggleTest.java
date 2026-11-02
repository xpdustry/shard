package com.xpdustry.shard.sim.blocks.defense;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.toy.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The toggle proof for {@code HashTest.GOLDEN} (WAVES.md "Golden hash"): the toy world has no regen projectors, and the
 * golden moved only because every world now has {@link RegenMends}, whose section holds an empty map until a regen
 * projector queues a heal. Without the part the scripted world hashes to the golden of lead/merge-3a at c15a0ac7
 * (DriverBolts and OreIndex included, on both sides); with it every other section is the same. W3.6's kinds and the
 * bullet tree add no other hash input.
 */
class RegenMendsHashToggleTest{
    static final long MAINS_GOLDEN = 0x92d71032f2a6e4d2L;

    static World churned(Systems systems){
        World world = new World(WorldConfig.of(ToyContent.INSTANCE, 48, 48, 42).withSystems(systems));
        world.part(WorldRules.class).rules.disableUnitCap = true;
        world.part(Chaos.class).enabled = true;
        for(int i = 0; i < 500; i++) world.tick();
        return world;
    }

    static Systems withoutMends(){
        var parts = new ArrayList<Class<? extends WorldPart>>();
        for(Class<? extends WorldPart> part : Registries.classes(WorldPart.class)) if(part != com.xpdustry.shard.sim.ai.command.CommandBatches.class && part != com.xpdustry.shard.sim.ai.team.TeamAis.class) if(part != RegenMends.class && part != com.xpdustry.shard.sim.objectives.ObjectiveState.class
                && part != com.xpdustry.shard.sim.fog.FogControl.class) parts.add(part);
        return Systems.of(Registries.instances(PhaseHandler.class), parts);
    }

    @Test
    void withoutTheMendMapTheGoldenIsMains(){
        assertEquals(MAINS_GOLDEN, com.xpdustry.shard.sim.spatial.SpatialHashes.before(churned(withoutMends())).value());
    }

    @Test
    void theMendMapAddsOnlyItsOwnSection(){
        WorldHash with = churned(com.xpdustry.shard.sim.ai.command.CommandBatchesHashToggleTest.builtInBefore()).hash(), without = churned(withoutMends()).hash();
        var expected = new ArrayList<>(without.sections());
        var actual = new ArrayList<>(with.sections());
        assertTrue(actual.removeIf(section -> section.name().equals(RegenMends.class.getName())), "the mend map's section");
        // W3.12's objective state came later, left out of both sides ({@code ObjectiveStateHashToggleTest})
        actual.removeIf(section -> section.name().equals(com.xpdustry.shard.sim.objectives.ObjectiveState.class.getName()));
        // W4.3's fog came later, left out of both sides ({@code FogHashToggleTest})
        actual.removeIf(section -> section.name().equals(com.xpdustry.shard.sim.fog.FogControl.class.getName()));
        assertEquals(expected, actual);
    }
}
