package com.xpdustry.shard.sim.world;

import com.xpdustry.shard.sim.game.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.waves.*;
import com.xpdustry.shard.sim.world.Observer;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WP83: a paused world ({@link World#paused()}, vanilla's {@code state.isPaused()}) stands still: units, buildings,
 * timers and game time, the random. Its ticks still count, admit inputs, count the enemies, run the posted tasks
 * (a dead team's clean-up) and check the game state (Logic.java:517-618). The flag is hashed only while set, and
 * forks and checkpoints carry it.
 */
class WorldPauseTest{
    World world;
    Rules rules;
    Observer events;

    @BeforeEach
    void world(){
        world = UnitWorlds.world(60, 60, 9);
        rules = world.part(WorldRules.class).rules;
        rules.waves = false;
        rules.pvp = true;
        rules.attackMode = true;
        rules.canGameOver = true;
        // no players here: PvpAutoPause would pause the world itself
        rules.pvpAutoPause = false;
        Teams.of(world).play();
        world.buildings().setBlock(10, 10, world.content().block("core-shard"), Team.SHARDED, 0, Cause.SERVER);
        world.buildings().setBlock(45, 45, world.content().block("core-shard"), Team.CRUX, 0, Cause.SERVER);
        world.buildings().setBlock(40, 40, world.content().block("copper-wall"), Team.CRUX, 0, Cause.SERVER);
        events = world.observe(1 << 12, List.of(GameEvent.class, TeamEvent.class), null);
    }

    String state(){
        var units = new StringBuilder();
        for(int i = 0; i < world.units().size(); i++){
            Unit u = world.units().get(i);
            units.append(u.id()).append(' ').append(u.x).append(',').append(u.y).append(' ').append(u.vel.x).append(';');
        }
        return units + " time " + world.timers().rawTime() + " rand " + world.rand().getState(0) + " pending " + world.timers().pending().size();
    }

    @Test
    void aPausedWorldStandsStillButCountsItsTicks(){
        Unit dagger = Unit.spawn(world, world.content().unit("dagger"), Team.CRUX, 200f, 200f, 0f);
        dagger.vel.set(1.5f, 0f);
        world.tick();
        String before = state();
        long ticks = world.ticks();
        WorldHash running = world.hash();

        world.paused(true);
        assertNotEquals(running, world.hash(), "the flag is hashed while set");
        for(int i = 0; i < 120; i++) world.tick();
        assertEquals(before, state(), "nothing moved, no timer ran, nothing drew");
        assertEquals(ticks + 120, world.ticks());
        assertEquals(1, WaveState.of(world).enemies, "the enemies are still counted");

        world.paused(false);
        world.tick();
        assertNotEquals(before, state(), "it moves again");
    }

    @Test
    void theGameStateAndPostedTasksStillRunWhilePaused(){
        world.paused(true);
        world.buildings().kill(world.buildings().at(45, 45));
        world.tick();
        List<WorldEvent> first = events.drain();
        assertEquals(1, first.stream().filter(GameEvent.GameOver.class::isInstance).count(), "game over while paused: " + first);
        assertEquals(Team.SHARDED, ((GameEvent.GameOver)first.stream().filter(GameEvent.GameOver.class::isInstance).findFirst().orElseThrow()).winner());
        assertTrue(first.stream().anyMatch(TeamEvent.SetTeams.class::isInstance), "the posted clean-up ran: " + first);
        assertEquals(Team.DERELICT, world.buildings().at(40, 40).team);
    }

    @Test
    void forksAndCheckpointsCarryThePause(){
        world.paused(true);
        world.tick();
        WorldHash hash = world.hash();
        World fork = world.fork();
        World restored = Checkpoint.decode(Checkpoint.capture(world).encode(Compression.FAST)).restore(world.config());
        assertTrue(fork.paused());
        assertTrue(restored.paused());
        assertEquals(hash, fork.hash());
        assertEquals(hash, restored.hash());
        world.paused(false);
        restored.paused(false);
        for(int i = 0; i < 60; i++){
            world.tick();
            restored.tick();
        }
        assertEquals(world.hash(), restored.hash());
    }
}
