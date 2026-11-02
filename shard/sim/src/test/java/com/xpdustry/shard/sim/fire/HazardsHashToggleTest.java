package com.xpdustry.shard.sim.fire;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.puddle.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.toy.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The toggle proof for {@code HashTest.GOLDEN} (WAVES.md "Golden hash"): the toy world starts no fire and spills nothing,
 * and the golden moved only because every world now has {@link Fires}, {@link Puddles} and {@link HazardPool}, whose
 * hash sections hold their empty maps and pool. Without them and {@link HazardPool.Update} the scripted world hashes to
 * main's golden ({@value #MAIN}), and with them every other section is the same.
 */
class HazardsHashToggleTest{
    /** Main's golden before W2.18; with W2.15's {@code Construction} section, W2.15's golden before it merged W2.18. */
    static final String MAIN = "0xf1feec9f2952bfab at 63e6c43";
    /** {@link #MAIN}, with W2.17's AI. */
    static final long GOLDEN_WITHOUT_HAZARDS = 0x51ca3a48d5cb3b21L;
    static final Set<Class<?>> PARTS = Set.of(Fires.class, Puddles.class, HazardPool.class);

    static World churned(Systems systems){
        World world = new World(WorldConfig.of(ToyContent.INSTANCE, 48, 48, 42).withSystems(systems));
        world.part(WorldRules.class).rules.disableUnitCap = true;
        world.part(Chaos.class).enabled = true;
        for(int i = 0; i < 500; i++) world.tick();
        return world;
    }

    static Systems withoutHazards(){
        var handlers = new ArrayList<PhaseHandler>();
        for(PhaseHandler handler : Registries.instances(PhaseHandler.class)) if(!(handler instanceof HazardPool.Update)) handlers.add(handler);
        var parts = new ArrayList<Class<? extends WorldPart>>();
        // W2.16's turret clusters, W2.23's logic units, W3.1's driver bolts and W3.7's ore index came later; this proof
        // holds without them ({@code UnitClustersHashToggleTest}, {@code LogicUnitsHashToggleTest},
        // {@code DriverBoltsHashToggleTest}, {@code OreIndexHashToggleTest},
        // {@code RegenMendsHashToggleTest})
        for(Class<? extends WorldPart> part : Registries.classes(WorldPart.class)) if(part != com.xpdustry.shard.sim.ai.command.CommandBatches.class && part != com.xpdustry.shard.sim.ai.team.TeamAis.class){
            if(!PARTS.contains(part) && part != com.xpdustry.shard.sim.blocks.turret.UnitClusters.class && part != com.xpdustry.shard.sim.logic.LogicUnits.class
                && part != com.xpdustry.shard.sim.unit.OreIndex.class
                && part != com.xpdustry.shard.sim.blocks.distribution.DriverBolts.class
                && part != com.xpdustry.shard.sim.blocks.defense.RegenMends.class && part != com.xpdustry.shard.sim.objectives.ObjectiveState.class
                && part != com.xpdustry.shard.sim.fog.FogControl.class) parts.add(part);
        }
        return Systems.of(handlers, parts);
    }

    @Test
    void withoutFiresAndPuddlesTheGoldenIsMains(){
        assertEquals(GOLDEN_WITHOUT_HAZARDS, com.xpdustry.shard.sim.spatial.SpatialHashes.before(churned(withoutHazards())).value());
    }

    @Test
    void firesAndPuddlesAddOnlyTheirOwnSections(){
        WorldHash with = churned(com.xpdustry.shard.sim.ai.command.CommandBatchesHashToggleTest.builtInBefore()).hash(), without = churned(withoutHazards()).hash();
        var expected = new ArrayList<>(without.sections());
        var actual = new ArrayList<>(with.sections());
        for(Class<?> part : PARTS) assertTrue(actual.removeIf(section -> section.name().equals(part.getName())), part.getSimpleName() + "'s section");
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
