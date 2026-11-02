package com.xpdustry.shard.sim.ai;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.sim.path.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.team.Team;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/** The wave AIs on their own: which controller a unit gets, that forks and checkpoints carry AI state, the move command. */
class WaveAiTest{
    static World waveWorld(){
        World world = VanillaWorlds.world(48, 48, 7);
        Rules rules = world.part(WorldRules.class).rules;
        rules.waves = true;
        rules.waveTimer = false;
        rules.disableUnitCap = true;
        rules.fire = false;
        world.part(PathService.class).invalidateAll();
        return world;
    }

    static Unit spawn(World world, String type, int team, float x, float y){
        return Unit.spawn(world, world.content().unit(type), team, x, y, 0f);
    }

    @Test
    void waveUnitsGetTheirTypesAi(){
        World world = waveWorld();
        assertInstanceOf(GroundAi.class, spawn(world, "dagger", Team.CRUX, 40f, 40f).controller);
        assertInstanceOf(FlyingAi.class, spawn(world, "flare", Team.CRUX, 40f, 40f).controller);
        assertInstanceOf(SuicideAi.class, spawn(world, "crawler", Team.CRUX, 40f, 40f).controller);
        assertInstanceOf(FlyingFollowAi.class, spawn(world, "quell", Team.CRUX, 40f, 40f).controller);
        assertInstanceOf(HugAi.class, spawn(world, "latum", Team.CRUX, 40f, 40f).controller);
        assertInstanceOf(DefenderAi.class, spawn(world, "oct", Team.CRUX, 40f, 40f).controller);
        assertInstanceOf(MissileAi.class, spawn(world, "quell-missile", Team.CRUX, 40f, 40f).controller);
        // the default team's units are commanded
        assertInstanceOf(CommandAi.class, spawn(world, "dagger", Team.SHARDED, 40f, 40f).controller);
    }

    @Test
    void daggersWalkToTheCoreAndForksAndCheckpointsAgree(){
        World world = waveWorld();
        world.buildings().create(world.content().block("core-shard"), Team.SHARDED, 40, 24, 0, Cause.SERVER);
        Unit dagger = spawn(world, "dagger", Team.CRUX, 6 * 8f, 24 * 8f);
        spawn(world, "flare", Team.CRUX, 4 * 8f, 8 * 8f);
        spawn(world, "crawler", Team.CRUX, 8 * 8f, 20 * 8f);
        for(int i = 0; i < 200; i++) world.tick();
        assertTrue(dagger.x > 6 * 8f + 40f, "the dagger walked toward the core: " + dagger.x);

        World fork = world.fork();
        World restored = Checkpoint.decode(Checkpoint.capture(world).encode(Compression.FAST)).restore(world.config());
        assertEquals(world.hash(), fork.hash(), "the fork");
        assertEquals(world.hash(), restored.hash(), "the restored world");
        for(int i = 0; i < 300; i++){
            world.tick();
            fork.tick();
            restored.tick();
            assertEquals(world.hash(), fork.hash(), "the fork after " + i);
            assertEquals(world.hash(), restored.hash(), "the restored world after " + i);
        }
        assertTrue(world.units().get(0).controller instanceof GroundAi ai && ai.target != null, "the dagger has a target");
    }

    @Test
    void aMoveCommandFliesAndWalksToItsTarget(){
        World world = waveWorld();
        Unit flare = spawn(world, "flare", Team.SHARDED, 40f, 40f);
        Unit dagger = spawn(world, "dagger", Team.SHARDED, 40f, 60f);
        // a wall between the dagger and its target: it paths around through the control pathfinder
        for(int y = 2; y < 20; y++) world.buildings().create(world.content().block("copper-wall"), Team.SHARDED, 20, y, 0, Cause.SERVER);
        var flying = (CommandAi)flare.controller;
        var walking = (CommandAi)dagger.controller;
        flying.commandPosition(new Vec2(300f, 300f));
        walking.commandPosition(new Vec2(30 * 8f, 6 * 8f));
        for(int i = 0; i < 1500 && (flying.targetPos != null || walking.targetPos != null); i++) world.tick();
        assertNull(flying.targetPos, "the flare arrived: " + flare.x + "," + flare.y);
        assertNull(walking.targetPos, "the dagger arrived: " + dagger.x + "," + dagger.y);
        assertTrue(flare.within(300f, 300f, 20f));
        assertTrue(dagger.within(30 * 8f, 6 * 8f, 20f));
    }

    @Test
    void stuckGroundUnitsStampTheAvoidanceGrid(){
        World world = waveWorld();
        AvoidanceProcess avoidance = world.part(AvoidanceProcess.class);
        assertNull(avoidance.getAvoidance(), "the first request activates the grid and gets nothing");
        Unit dagger = spawn(world, "dagger", Team.CRUX, 10 * 8f, 10 * 8f);
        // the wave team is an AI team: its ground units stamp; the default team's don't
        spawn(world, "dagger", Team.SHARDED, 30 * 8f, 30 * 8f);
        // tick 1 begins before the first recount, so its stamps are empty; tick 2 stamps the dagger into the buffer
        // tick 3 reads (vanilla's double buffer)
        for(int i = 0; i < 3; i++) world.tick();
        int[] grid = avoidance.avoidance();
        assertNotNull(grid);
        assertEquals(Integer.MAX_VALUE - dagger.id(), grid[10 + 10 * 48]);
        assertEquals(0, grid[30 + 30 * 48]);
    }
}
