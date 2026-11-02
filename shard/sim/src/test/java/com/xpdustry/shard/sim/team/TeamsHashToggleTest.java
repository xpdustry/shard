package com.xpdustry.shard.sim.team;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.game.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.toy.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The toggle proof for {@code HashTest.GOLDEN} (WAVES.md "Golden hash"): the toy world has no cores, and the golden
 * moved only because every world now has {@link Teams}, whose hash section holds the active list. Without it and its
 * phase handlers ({@link TeamCoreStats}, {@link CoreFill}, {@link CheckGameState}) the scripted world hashes to the
 * golden from before (main's 0x87c86d0b411ad384 at f3e806b; with W2.8's combat 0x2a20458043e74979; with W2.18's fires
 * and puddles 0xd316c779e33b77da; with W2.15's construction 0x36d174916494d372; with W2.17's AI 0xe07bc1e065033b4c), and
 * with them every other section is the same.
 */
class TeamsHashToggleTest{
    static final long GOLDEN_WITHOUT_TEAMS = 0xe07bc1e065033b4cL;

    static World churned(Systems systems){
        World world = new World(WorldConfig.of(ToyContent.INSTANCE, 48, 48, 42).withSystems(systems));
        world.part(WorldRules.class).rules.disableUnitCap = true;
        world.part(Chaos.class).enabled = true;
        for(int i = 0; i < 500; i++) world.tick();
        return world;
    }

    static Systems withoutTeams(){
        var handlers = new ArrayList<PhaseHandler>();
        for(PhaseHandler handler : Registries.instances(PhaseHandler.class)){
            if(!(handler instanceof TeamCoreStats || handler instanceof CoreFill || handler instanceof CheckGameState)) handlers.add(handler);
        }
        var parts = new ArrayList<Class<? extends WorldPart>>();
        // W2.16's turret clusters, W2.23's logic units, W3.1's driver bolts and W3.7's ore index came later; this proof
        // holds without them ({@code UnitClustersHashToggleTest}, {@code LogicUnitsHashToggleTest},
        // {@code DriverBoltsHashToggleTest}, {@code OreIndexHashToggleTest},
        // {@code RegenMendsHashToggleTest})
        for(Class<? extends WorldPart> part : Registries.classes(WorldPart.class)) if(part != com.xpdustry.shard.sim.ai.command.CommandBatches.class && part != com.xpdustry.shard.sim.ai.team.TeamAis.class){
            if(part != Teams.class && part != com.xpdustry.shard.sim.blocks.turret.UnitClusters.class && part != com.xpdustry.shard.sim.logic.LogicUnits.class
                && part != com.xpdustry.shard.sim.unit.OreIndex.class
                && part != com.xpdustry.shard.sim.blocks.distribution.DriverBolts.class
                && part != com.xpdustry.shard.sim.blocks.defense.RegenMends.class && part != com.xpdustry.shard.sim.objectives.ObjectiveState.class
                && part != com.xpdustry.shard.sim.fog.FogControl.class) parts.add(part);
        }
        return Systems.of(handlers, parts);
    }

    @Test
    void withoutTeamsTheGoldenIsMains(){
        assertEquals(GOLDEN_WITHOUT_TEAMS, com.xpdustry.shard.sim.spatial.SpatialHashes.before(churned(withoutTeams())).value());
    }

    @Test
    void teamsAddOnlyTheirOwnSection(){
        WorldHash with = churned(com.xpdustry.shard.sim.ai.command.CommandBatchesHashToggleTest.builtInBefore()).hash(), without = churned(withoutTeams()).hash();
        var expected = new ArrayList<>(without.sections());
        var actual = new ArrayList<>(with.sections());
        assertTrue(actual.removeIf(section -> section.name().equals(Teams.class.getName())), "the teams section");
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
