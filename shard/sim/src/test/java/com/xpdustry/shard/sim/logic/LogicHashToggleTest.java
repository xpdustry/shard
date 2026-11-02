package com.xpdustry.shard.sim.logic;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.toy.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The toggle proof for {@code HashTest.GOLDEN} (WAVES.md "Golden hash"): the toy world has no processors, and the
 * golden moved only because every world now has a {@link LogicPart}, whose hash section holds the logic clock. Without
 * the part and its two phase handlers the scripted world hashes to the golden from before, and with them every other
 * section is the same.
 */
class LogicHashToggleTest{
    /**
     * {@code HashTest.GOLDEN} without W2.9's logic part: main before W2.9 (11cf042) was {@code 0xe52079404b31b55cL};
     * with W2.12's {@code Teams} section ({@code TeamsHashToggleTest}), W2.8's combat, W2.18's fires and puddles and
     * W2.15's {@code Construction} section ({@code ConstructionHashToggleTest}) it was {@code 0x6cbcec90100796eaL} (main
     * alone: {@code 0x6e66deaa78fe1932L}; before W2.18: {@code 0x8859c4c88755bcdaL}; before W2.15:
     * {@code 0x01fd093f4253757eL}); with W2.17's AI it is this.
     */
    static final long GOLDEN_WITHOUT_LOGIC = 0xded4b4163112e55bL;

    static World churned(Systems systems){
        World world = new World(WorldConfig.of(ToyContent.INSTANCE, 48, 48, 42).withSystems(systems));
        world.part(WorldRules.class).rules.disableUnitCap = true;
        world.part(Chaos.class).enabled = true;
        for(int i = 0; i < 500; i++) world.tick();
        return world;
    }

    static Systems withoutLogic(){
        var handlers = new ArrayList<PhaseHandler>();
        for(PhaseHandler handler : Registries.instances(PhaseHandler.class)){
            if(!(handler instanceof LogicPart.LogicClock || handler instanceof LogicPart.LogicTickEnd)) handlers.add(handler);
        }
        var parts = new ArrayList<Class<? extends WorldPart>>();
        // W2.16's turret clusters, W2.23's logic units, W3.1's driver bolts and W3.7's ore index came later; this proof
        // holds without them ({@code UnitClustersHashToggleTest}, {@code LogicUnitsHashToggleTest},
        // {@code DriverBoltsHashToggleTest}, {@code OreIndexHashToggleTest},
        // {@code RegenMendsHashToggleTest})
        for(Class<? extends WorldPart> part : Registries.classes(WorldPart.class)) if(part != com.xpdustry.shard.sim.ai.command.CommandBatches.class && part != com.xpdustry.shard.sim.ai.team.TeamAis.class){
            if(part != LogicPart.class && part != com.xpdustry.shard.sim.blocks.turret.UnitClusters.class && part != LogicUnits.class
                && part != com.xpdustry.shard.sim.unit.OreIndex.class
                && part != com.xpdustry.shard.sim.blocks.distribution.DriverBolts.class
                && part != com.xpdustry.shard.sim.blocks.defense.RegenMends.class && part != com.xpdustry.shard.sim.objectives.ObjectiveState.class
                && part != com.xpdustry.shard.sim.fog.FogControl.class) parts.add(part);
        }
        return Systems.of(handlers, parts);
    }

    @Test
    void withoutTheLogicPartTheGoldenIsTheOldOne(){
        assertEquals(GOLDEN_WITHOUT_LOGIC, com.xpdustry.shard.sim.spatial.SpatialHashes.before(churned(withoutLogic())).value());
    }

    @Test
    void theLogicPartAddsOnlyItsOwnSection(){
        WorldHash with = churned(com.xpdustry.shard.sim.ai.command.CommandBatchesHashToggleTest.builtInBefore()).hash(), without = churned(withoutLogic()).hash();
        var expected = new ArrayList<>(without.sections());
        var actual = new ArrayList<>(with.sections());
        assertTrue(actual.removeIf(section -> section.name().equals(LogicPart.class.getName())), "the logic section");
        actual.removeIf(section -> section.name().equals(com.xpdustry.shard.sim.blocks.turret.UnitClusters.class.getName()));
        actual.removeIf(section -> section.name().equals(LogicUnits.class.getName()));
        actual.removeIf(section -> section.name().equals(com.xpdustry.shard.sim.unit.OreIndex.class.getName()));
        actual.removeIf(section -> section.name().equals(com.xpdustry.shard.sim.blocks.distribution.DriverBolts.class.getName()));
        actual.removeIf(section -> section.name().equals(com.xpdustry.shard.sim.blocks.defense.RegenMends.class.getName()));
        actual.removeIf(section -> section.name().equals(com.xpdustry.shard.sim.objectives.ObjectiveState.class.getName()));
        // W4.3's fog came later, left out of both sides ({@code FogHashToggleTest})
        actual.removeIf(section -> section.name().equals(com.xpdustry.shard.sim.fog.FogControl.class.getName()));
        assertEquals(expected, actual);
    }
}
