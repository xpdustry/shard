package com.xpdustry.shard.sim.fog;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.toy.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The toggle proof for {@code HashTest.GOLDEN} (WAVES.md "Golden hash"): the golden moved only because every world now
 * has {@link FogControl}, whose section holds the map size and no fog in the toy world, whose rules have no fog. Every
 * other fog input (unit and building hooks, the turret refresh) acts only under {@code rules.fog}. Without the part the
 * scripted world hashes to main's golden at 9ae67530, and with it every other section is the same.
 */
class FogHashToggleTest{
    static final long MAINS_GOLDEN = 0xc5c5f118553c790aL;

    static World churned(Systems systems){
        World world = new World(WorldConfig.of(ToyContent.INSTANCE, 48, 48, 42).withSystems(systems));
        world.part(WorldRules.class).rules.disableUnitCap = true;
        world.part(Chaos.class).enabled = true;
        for(int i = 0; i < 500; i++) world.tick();
        return world;
    }

    static Systems withoutFog(){
        var parts = new ArrayList<Class<? extends WorldPart>>();
        for(Class<? extends WorldPart> part : Registries.classes(WorldPart.class)) if(part != com.xpdustry.shard.sim.ai.command.CommandBatches.class && part != com.xpdustry.shard.sim.ai.team.TeamAis.class) if(part != FogControl.class) parts.add(part);
        return Systems.of(Registries.instances(PhaseHandler.class), parts);
    }

    @Test
    void withoutTheFogTheGoldenIsMains(){
        assertEquals(MAINS_GOLDEN, com.xpdustry.shard.sim.spatial.SpatialHashes.before(churned(withoutFog())).value());
    }

    @Test
    void theFogAddsOnlyItsOwnSection(){
        WorldHash with = churned(com.xpdustry.shard.sim.ai.command.CommandBatchesHashToggleTest.builtInBefore()).hash(), without = churned(withoutFog()).hash();
        var expected = new ArrayList<>(without.sections());
        var actual = new ArrayList<>(with.sections());
        assertTrue(actual.removeIf(section -> section.name().equals(FogControl.class.getName())), "the fog's section");
        assertEquals(expected, actual);
    }

    /** The world's fog is off: the part holds no team's data after play, and units hide from no one. */
    @Test
    void withoutFogRulesNothingIsHidden(){
        World world = churned(com.xpdustry.shard.sim.ai.command.CommandBatchesHashToggleTest.builtInBefore());
        FogControl fog = FogControl.of(world);
        for(int team = 0; team < 256; team++) assertFalse(fog.hasData(team), "team " + team);
        for(int i = 0; i < world.units().size(); i++){
            var unit = world.units().get(i);
            for(int team = 0; team < 8; team++) assertFalse(unit.inFogTo(team));
        }
    }
}
