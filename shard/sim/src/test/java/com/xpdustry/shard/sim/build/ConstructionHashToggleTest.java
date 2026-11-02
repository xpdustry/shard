package com.xpdustry.shard.sim.build;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.toy.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The toggle proof for {@code HashTest.GOLDEN} (WAVES.md "Golden hash"): the toy world builds nothing (its units have
 * no build speed and nobody plans), and the golden moved only because every world now has {@link Construction}, whose
 * hash section holds the place sound's clock. Without it the scripted world hashes to main's golden from before
 * (0x39ab184746df7606 at 5d299f5; with W2.17's AI 0x98a2129e57e3a1b0), and with it every other section is the same.
 */
class ConstructionHashToggleTest{
    static final long GOLDEN_WITHOUT_CONSTRUCTION = 0x98a2129e57e3a1b0L;

    static World churned(Systems systems){
        World world = new World(WorldConfig.of(ToyContent.INSTANCE, 48, 48, 42).withSystems(systems));
        world.part(WorldRules.class).rules.disableUnitCap = true;
        world.part(Chaos.class).enabled = true;
        for(int i = 0; i < 500; i++) world.tick();
        return world;
    }

    static Systems withoutConstruction(){
        var handlers = new ArrayList<PhaseHandler>();
        for(Phase phase : Phase.values()) handlers.addAll(Systems.builtIn().handlers(phase));
        var parts = new ArrayList<Class<? extends WorldPart>>();
        // W2.16's turret clusters, W2.23's logic units, W3.1's driver bolts and W3.7's ore index came later; this proof
        // holds without them ({@code UnitClustersHashToggleTest}, {@code LogicUnitsHashToggleTest},
        // {@code DriverBoltsHashToggleTest}, {@code OreIndexHashToggleTest},
        // {@code RegenMendsHashToggleTest})
        for(Class<? extends WorldPart> part : Registries.classes(WorldPart.class)) if(part != com.xpdustry.shard.sim.ai.command.CommandBatches.class && part != com.xpdustry.shard.sim.ai.team.TeamAis.class){
            if(part != Construction.class && part != com.xpdustry.shard.sim.blocks.turret.UnitClusters.class && part != com.xpdustry.shard.sim.logic.LogicUnits.class
                && part != com.xpdustry.shard.sim.unit.OreIndex.class
                && part != com.xpdustry.shard.sim.blocks.distribution.DriverBolts.class
                && part != com.xpdustry.shard.sim.blocks.defense.RegenMends.class && part != com.xpdustry.shard.sim.objectives.ObjectiveState.class
                && part != com.xpdustry.shard.sim.fog.FogControl.class) parts.add(part);
        }
        return Systems.of(handlers, parts);
    }

    @Test
    void withoutConstructionTheGoldenIsMains(){
        assertEquals(GOLDEN_WITHOUT_CONSTRUCTION, com.xpdustry.shard.sim.spatial.SpatialHashes.before(churned(withoutConstruction())).value());
    }

    @Test
    void constructionAddsOnlyItsOwnSection(){
        WorldHash with = churned(com.xpdustry.shard.sim.ai.command.CommandBatchesHashToggleTest.builtInBefore()).hash(), without = churned(withoutConstruction()).hash();
        var expected = new ArrayList<>(without.sections());
        var actual = new ArrayList<>(with.sections());
        assertTrue(actual.removeIf(section -> section.name().equals(Construction.class.getName())), "the construction section");
        actual.removeIf(section -> section.name().equals(com.xpdustry.shard.sim.blocks.turret.UnitClusters.class.getName()));
        actual.removeIf(section -> section.name().equals(com.xpdustry.shard.sim.logic.LogicUnits.class.getName()));
        actual.removeIf(section -> section.name().equals(com.xpdustry.shard.sim.unit.OreIndex.class.getName()));
        actual.removeIf(section -> section.name().equals(com.xpdustry.shard.sim.blocks.distribution.DriverBolts.class.getName()));
        actual.removeIf(section -> section.name().equals(com.xpdustry.shard.sim.blocks.defense.RegenMends.class.getName()));
        actual.removeIf(section -> section.name().equals(com.xpdustry.shard.sim.objectives.ObjectiveState.class.getName()));
        // W4.3's fog came later, left out of both sides ({@code FogHashToggleTest})
        actual.removeIf(section -> section.name().equals(com.xpdustry.shard.sim.fog.FogControl.class.getName()));
        assertEquals(expected, actual);
    }
}
