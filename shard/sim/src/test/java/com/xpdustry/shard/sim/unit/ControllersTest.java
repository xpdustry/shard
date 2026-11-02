package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.sim.ai.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The core units' controllers ({@link Controllers#create}), one case each: Serpulo's lambda builds only on AI teams
 * ({@code u -> u.team.isAI() ? new BuilderAI(true, 400f) : new CommandAI()}, UnitTypes.java:2495), Erekir's builds
 * on every team ({@code u -> new BuilderAI(true, coreFleeRange)}, coreFleeRange 500, UnitTypes.java:4352-4501,
 * checked in the v160.7 bytecode: no {@code isAI} test).
 */
class ControllersTest{
    World world;

    @BeforeEach
    void world(){
        world = UnitWorlds.world(40, 40, 3);
        Rules rules = world.part(WorldRules.class).rules;
        // waves on, crux the wave team: crux is an AI team, sharded isn't
        rules.waves = true;
        rules.pvp = false;
        assertTrue(UnitCalls.isAI(world, Team.CRUX));
        assertFalse(UnitCalls.isAI(world, Team.SHARDED));
    }

    UnitController controller(String type, int team){
        return Unit.spawn(world, world.content().unit(type), team, 100f, 100f, 0f).controller;
    }

    static void fleeingBuilder(UnitController controller, float range, String what){
        BuilderAi builder = assertInstanceOf(BuilderAi.class, controller, what);
        assertTrue(builder.alwaysFlee, what + " always flees");
        assertEquals(range, builder.fleeRange, what + "'s flee range");
    }

    @Test
    void serpuloCoreUnitsBuildOnlyOnAiTeams(){
        for(String type : new String[]{"alpha", "beta", "gamma"}){
            fleeingBuilder(controller(type, Team.CRUX), 400f, type + " on crux");
            assertInstanceOf(CommandAi.class, controller(type, Team.SHARDED), type + " on sharded");
        }
    }

    @Test
    void erekirCoreUnitsBuildOnEveryTeam(){
        for(String type : new String[]{"evoke", "incite", "emanate"}){
            fleeingBuilder(controller(type, Team.CRUX), 500f, type + " on crux");
            fleeingBuilder(controller(type, Team.SHARDED), 500f, type + " on sharded");
        }
    }
}
