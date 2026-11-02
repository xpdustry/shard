package com.xpdustry.shard.sim.blocks.turret;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.toy.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The toggle proof for {@code HashTest.GOLDEN} (WAVES.md "Golden hash"): the toy world has no turrets, and the golden
 * moved only because every world now has {@link UnitClusters} (ripples' {@code sort:grouped}), whose hash section is
 * empty until a turret reads it. Without the part the scripted world hashes to the golden without turrets: main's at
 * 99254f6 was {@code 0x57d848b61f976f02L}; with W2.17's AI it is this, W2.17's own golden. With the part every other
 * section is the same.
 */
class UnitClustersHashToggleTest{
    static final long MAINS_GOLDEN = 0x647bd5b7b92b399aL;

    static World churned(Systems systems){
        World world = new World(WorldConfig.of(ToyContent.INSTANCE, 48, 48, 42).withSystems(systems));
        world.part(WorldRules.class).rules.disableUnitCap = true;
        world.part(Chaos.class).enabled = true;
        for(int i = 0; i < 500; i++) world.tick();
        return world;
    }

    static Systems withoutClusters(){
        var parts = new ArrayList<Class<? extends WorldPart>>();
        // W2.23's logic units, W3.1's driver bolts and W3.7's ore index came later; this proof holds without them
        for(Class<? extends WorldPart> part : Registries.classes(WorldPart.class)) if(part != com.xpdustry.shard.sim.ai.command.CommandBatches.class && part != com.xpdustry.shard.sim.ai.team.TeamAis.class){
            if(part != UnitClusters.class && part != com.xpdustry.shard.sim.logic.LogicUnits.class && part != com.xpdustry.shard.sim.unit.OreIndex.class
                && part != com.xpdustry.shard.sim.blocks.distribution.DriverBolts.class
                && part != com.xpdustry.shard.sim.blocks.defense.RegenMends.class && part != com.xpdustry.shard.sim.objectives.ObjectiveState.class
                && part != com.xpdustry.shard.sim.fog.FogControl.class) parts.add(part);
        }
        return Systems.of(Registries.instances(PhaseHandler.class), parts);
    }

    @Test
    void withoutTheClustersTheGoldenIsMains(){
        assertEquals(MAINS_GOLDEN, com.xpdustry.shard.sim.spatial.SpatialHashes.before(churned(withoutClusters())).value());
    }

    @Test
    void theClustersAddOnlyTheirOwnSection(){
        WorldHash with = churned(com.xpdustry.shard.sim.ai.command.CommandBatchesHashToggleTest.builtInBefore()).hash(), without = churned(withoutClusters()).hash();
        var expected = new ArrayList<>(without.sections());
        var actual = new ArrayList<>(with.sections());
        assertTrue(actual.removeIf(section -> section.name().equals(UnitClusters.class.getName())), "the clusters section");
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
