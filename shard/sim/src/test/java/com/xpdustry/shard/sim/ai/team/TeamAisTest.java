package com.xpdustry.shard.sim.ai.team;

import com.xpdustry.shard.sim.ai.CommandAi;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.path.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.team.Team;
import com.xpdustry.shard.sim.team.Teams;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The team AIs on Shard alone: they start only with their rules, the RTS AI sends crux's squad at sharded's core and
 * the base builder plans parts, and forks and checkpoint restores taken while both run stay hash-exact. Parity with
 * vanilla is {@code TeamAiOracleTest}'s.
 */
class TeamAisTest{
    static World world(boolean rts, boolean build){
        World world = VanillaWorlds.world(110, 70, 7);
        for(int y = 0; y < 70; y++){
            for(int x = 0; x < 60; x++) world.tiles().overlay(world.tiles().pos(x, y), world.content().block("ore-copper").id());
        }
        Rules rules = world.part(WorldRules.class).rules;
        rules.waveTimer = false;
        rules.disableUnitCap = true;
        rules.fire = false;
        TeamRule crux = rules.teams.get(Team.CRUX);
        crux.rtsAi = rts;
        crux.buildAi = build;
        crux.buildSpeedMultiplier = 20f;
        world.buildings().setBlock(30, 35, world.content().block("core-shard"), Team.CRUX, 0, Cause.SERVER);
        world.buildings().setBlock(100, 35, world.content().block("core-shard"), Team.SHARDED, 0, Cause.SERVER);
        world.part(PathService.class).invalidateAll();
        for(int i = 0; i < 4; i++) Unit.spawn(world, world.content().unit("dagger"), Team.CRUX, (40 + i * 2) * 8f, 35 * 8f, 0f);
        return world;
    }

    @Test
    void noRuleNoAi(){
        World world = world(false, false);
        for(int i = 0; i < 300; i++) world.tick();
        assertNull(TeamAis.of(world).rts(Team.CRUX));
        assertNull(TeamAis.of(world).builder(Team.CRUX));
        assertTrue(Teams.of(world).get(Team.CRUX).plans().isEmpty());
    }

    @Test
    void theRtsAiSendsItsSquadAtTheEnemyCore(){
        World world = world(true, false);
        boolean sent = false;
        for(int i = 0; i < 400 && !sent; i++){
            world.tick();
            for(int u = 0; u < world.units().size(); u++){
                Unit unit = world.units().get(u);
                if(unit.team == Team.CRUX && unit.controller instanceof CommandAi ai && ai.attackTarget instanceof Building b && b.team == Team.SHARDED){
                    sent = true;
                    assertEquals(1.0, unit.flag, "a squad sent out is mobilized");
                }
            }
        }
        assertTrue(sent, "the squad was sent at sharded's core");
        assertNotNull(TeamAis.of(world).rts(Team.CRUX));
    }

    @Test
    void theBaseBuilderFillsItsCoreAndPlansParts(){
        World world = world(false, true);
        int most = 0;
        for(int i = 0; i < 600; i++){
            world.tick();
            most = Math.max(most, Teams.of(world).get(Team.CRUX).plans().size());
        }
        var core = Teams.of(world).core(Team.CRUX);
        assertEquals(core.getMaximumAccepted(world.content().item("copper").id()), core.items.get(world.content().item("copper").id()), "the core is full");
        assertTrue(most > 0, "parts were planned");
        assertTrue(TeamAis.of(world).builder(Team.CRUX).foundPath() || TeamAis.of(world).builder(Team.CRUX).path().length == 0);
    }

    @Test
    void forksAndCheckpointsCarryBothAis(){
        World world = world(true, true);
        for(int i = 0; i < 250; i++) world.tick();
        assertNotNull(TeamAis.of(world).rts(Team.CRUX));
        assertNotNull(TeamAis.of(world).builder(Team.CRUX));
        World fork = world.fork();
        World restored = Checkpoint.decode(Checkpoint.capture(world).encode(Compression.FAST)).restore(world.config());
        assertEquals(world.hash(), fork.hash(), "the fork");
        assertEquals(world.hash(), restored.hash(), "the restored world");
        for(int i = 0; i < 500; i++){
            world.tick();
            fork.tick();
            restored.tick();
            assertEquals(world.hash(), fork.hash(), "the fork after " + i);
            assertEquals(world.hash(), restored.hash(), "the restored world after " + i);
        }
    }

    /**
     * A team that dies keeps its AIs, as vanilla's {@code TeamData} keeps {@code rtsAi} and {@code buildAi} through
     * {@code destroyToDerelict} (Teams.java:331): they pause while the team is inactive (Logic.java:552) and resume if
     * it gets a core back. The RTS AI's damaged buildings may then be dead; forks and checkpoints carry them.
     */
    @Test
    void aDeadTeamsAisPauseAndSurviveCheckpoints(){
        World world = world(true, true);
        // crux is the wave team, which waves keep active (TeamData.active)
        world.part(WorldRules.class).rules.waves = false;
        for(int i = 0; i < 130; i++) world.tick();
        Building generator = world.buildings().setBlock(40, 45, world.content().block("combustion-generator"), Team.CRUX, 0, Cause.SERVER);
        TeamAis.buildDamaged(generator);
        RtsAi rts = TeamAis.of(world).rts(Team.CRUX);
        assertEquals(java.util.List.of(generator), rts.damaged());
        generator.kill();
        Teams.of(world).core(Team.CRUX).kill();
        float[] timers = rts.timers.clone();
        for(int i = 0; i < 300; i++) world.tick();
        assertSame(rts, TeamAis.of(world).rts(Team.CRUX), "the dead team keeps its AI");
        assertArrayEquals(timers, rts.timers, "and it does not run");
        assertEquals(java.util.List.of(generator), rts.damaged(), "its damaged buildings wait for the next round");
        World fork = world.fork();
        World restored = Checkpoint.decode(Checkpoint.capture(world).encode(Compression.FAST)).restore(world.config());
        assertEquals(world.hash(), fork.hash(), "the fork");
        assertEquals(world.hash(), restored.hash(), "the restored world");
        // the team comes back: both AIs resume, the dead building is dropped with the next round
        for(World w : new World[]{world, fork, restored}){
            w.buildings().setBlock(30, 35, w.content().block("core-shard"), Team.CRUX, 0, Cause.SERVER);
        }
        for(int i = 0; i < 300; i++){
            world.tick();
            fork.tick();
            restored.tick();
            assertEquals(world.hash(), fork.hash(), "the fork after " + i);
            assertEquals(world.hash(), restored.hash(), "the restored world after " + i);
        }
        assertTrue(rts.damaged().isEmpty(), "the round took the damaged buildings");
    }

    @Test
    void theRegistryReadsEveryPart(){
        World world = world(false, false);
        BaseRegistry bases = TeamAis.of(world).bases(world);
        assertEquals(37, bases.cores.size());
        assertEquals(27, bases.parts.size());
        int required = 0;
        for(ContentRef ref : bases.resources()) required += bases.forResource(ref).size();
        assertEquals(146, required);
    }
}
