package com.xpdustry.shard.sim.logic;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.toy.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The toggle proof for {@code HashTest.GOLDEN} (WAVES.md "Golden hash"): the toy world runs no processor, and the
 * golden moved only because every world now has {@link LogicUnits} (the unit statements' timeouts, ore index and unit
 * trees), whose hash section holds its empty state. Without the part the scripted world hashes to
 * main's golden at 98ea527, and with it every other section is the same. W3.7's {@code OreIndex}, which took the ore
 * index over, came later, and W3.6's {@code RegenMends}: both sides leave them out ({@code OreIndexHashToggleTest},
 * {@code RegenMendsHashToggleTest}).
 */
class LogicUnitsHashToggleTest{
    static final long MAINS_GOLDEN = 0x0cf57340ca55d1cdL;

    static World churned(Systems systems){
        World world = new World(WorldConfig.of(ToyContent.INSTANCE, 48, 48, 42).withSystems(systems));
        world.part(WorldRules.class).rules.disableUnitCap = true;
        world.part(Chaos.class).enabled = true;
        for(int i = 0; i < 500; i++) world.tick();
        return world;
    }

    static Systems without(Set<Class<? extends WorldPart>> excluded){
        var parts = new ArrayList<Class<? extends WorldPart>>();
        for(Class<? extends WorldPart> part : Registries.classes(WorldPart.class)) if(part != com.xpdustry.shard.sim.ai.command.CommandBatches.class && part != com.xpdustry.shard.sim.ai.team.TeamAis.class) if(!excluded.contains(part)) parts.add(part);
        return Systems.of(Registries.instances(PhaseHandler.class), parts);
    }

    /** The built-in systems as W2.23 merged them: without the later OreIndex and RegenMends. */
    static Systems withLogicUnits(){
        return without(Set.of(com.xpdustry.shard.sim.unit.OreIndex.class, com.xpdustry.shard.sim.blocks.defense.RegenMends.class, com.xpdustry.shard.sim.objectives.ObjectiveState.class, com.xpdustry.shard.sim.fog.FogControl.class));
    }

    /** Without the part; its handlers then find nothing to do. */
    static Systems withoutLogicUnits(){
        return without(Set.of(LogicUnits.class, com.xpdustry.shard.sim.blocks.distribution.DriverBolts.class, com.xpdustry.shard.sim.unit.OreIndex.class,
            com.xpdustry.shard.sim.blocks.defense.RegenMends.class, com.xpdustry.shard.sim.objectives.ObjectiveState.class, com.xpdustry.shard.sim.fog.FogControl.class));
    }

    @Test
    void withoutTheLogicUnitsTheGoldenIsMains(){
        assertEquals(MAINS_GOLDEN, com.xpdustry.shard.sim.spatial.SpatialHashes.before(churned(withoutLogicUnits())).value());
    }

    @Test
    void theLogicUnitsAddOnlyTheirOwnSection(){
        WorldHash with = churned(withLogicUnits()).hash(), without = churned(withoutLogicUnits()).hash();
        var expected = new ArrayList<>(without.sections());
        var actual = new ArrayList<>(with.sections());
        assertTrue(actual.removeIf(section -> section.name().equals(LogicUnits.class.getName())), "the logic units section");
        // W3.1's mass driver bolts came later, left out of both sides ({@code DriverBoltsHashToggleTest})
        actual.removeIf(section -> section.name().equals(com.xpdustry.shard.sim.blocks.distribution.DriverBolts.class.getName()));
        assertEquals(expected, actual);
    }
}
