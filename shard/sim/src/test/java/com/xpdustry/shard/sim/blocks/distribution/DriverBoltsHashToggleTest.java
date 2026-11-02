package com.xpdustry.shard.sim.blocks.distribution;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.toy.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The toggle proof for {@code HashTest.GOLDEN} (WAVES.md "Golden hash"): the toy world has no mass drivers, and the
 * golden moved only because every world now has {@link DriverBolts}, whose hash section is empty until a driver fires.
 * Without the part the scripted world hashes to main's golden at 15a3ca9c, and with it every other section is the same.
 * W3.7's {@code OreIndex} came later: both sides leave it out, and {@code LogicUnits}' section, whose ore half moved
 * into it, is fed as it was then ({@code OreHashes}, {@code OreIndexHashToggleTest}). W3.6's {@code RegenMends} came
 * later too: both sides leave it out ({@code RegenMendsHashToggleTest}).
 */
class DriverBoltsHashToggleTest{
    static final long MAINS_GOLDEN = 0xa4b7fc2bfe38d3beL;

    static World churned(Systems systems){
        World world = new World(WorldConfig.of(ToyContent.INSTANCE, 48, 48, 42).withSystems(systems));
        world.part(WorldRules.class).rules.disableUnitCap = true;
        world.part(Chaos.class).enabled = true;
        for(int i = 0; i < 500; i++) world.tick();
        return world;
    }

    static Systems withoutBolts(){
        var parts = new ArrayList<Class<? extends WorldPart>>();
        for(Class<? extends WorldPart> part : Registries.classes(WorldPart.class)) if(part != com.xpdustry.shard.sim.ai.command.CommandBatches.class && part != com.xpdustry.shard.sim.ai.team.TeamAis.class){
            if(part != DriverBolts.class && part != OreIndex.class && part != com.xpdustry.shard.sim.blocks.defense.RegenMends.class && part != com.xpdustry.shard.sim.objectives.ObjectiveState.class
                && part != com.xpdustry.shard.sim.fog.FogControl.class) parts.add(part);
        }
        return Systems.of(Registries.instances(PhaseHandler.class), parts);
    }

    @Test
    void withoutTheBoltsTheGoldenIsMains(){
        World without = churned(withoutBolts());
        // W6.1's fill hitboxes came later, left out ({@code SpatialHashToggleTest})
        assertEquals(MAINS_GOLDEN, com.xpdustry.shard.sim.spatial.SpatialHashes.before(without,
            OreHashes.before(without, OreIndex.of(churned(com.xpdustry.shard.sim.ai.command.CommandBatchesHashToggleTest.builtInBefore())))).value());
    }

    @Test
    void theBoltsAddOnlyTheirOwnSection(){
        WorldHash with = churned(com.xpdustry.shard.sim.ai.command.CommandBatchesHashToggleTest.builtInBefore()).hash(), without = churned(withoutBolts()).hash();
        var expected = new ArrayList<>(without.sections());
        var actual = new ArrayList<>(with.sections());
        assertTrue(actual.removeIf(section -> section.name().equals(DriverBolts.class.getName())), "the bolts section");
        actual.removeIf(section -> section.name().equals(OreIndex.class.getName()));
        actual.removeIf(section -> section.name().equals(com.xpdustry.shard.sim.blocks.defense.RegenMends.class.getName()));
        actual.removeIf(section -> section.name().equals(com.xpdustry.shard.sim.objectives.ObjectiveState.class.getName()));
        // W4.3's fog came later, left out of both sides ({@code FogHashToggleTest})
        actual.removeIf(section -> section.name().equals(com.xpdustry.shard.sim.fog.FogControl.class.getName()));
        assertEquals(expected, actual);
    }
}
