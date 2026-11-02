package com.xpdustry.shard.sim.game;

import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.waves.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.sim.world.Observer;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** {@link CheckGameState}'s three ends of a game, once each, and the rules that keep it going. */
class CheckGameStateTest{
    World world;
    Rules rules;
    Observer events;

    @BeforeEach
    void world(){
        world = UnitWorlds.world(40, 40, 3);
        rules = world.part(WorldRules.class).rules;
        events = world.observe(64, List.of(GameEvent.class), null);
    }

    void place(String block, int x, int y, int team){
        world.buildings().setBlock(x, y, VanillaWorlds.VANILLA.block(block), team, 0, Cause.SERVER);
    }

    /** Starts the game ({@link Play}), then runs {@code count} ticks. */
    List<WorldEvent> ticks(int count){
        if(!Teams.of(world).playing()) Play.play(world);
        for(int i = 0; i < count; i++) world.tick();
        return events.drain();
    }

    @Test
    void survivalEndsWhenTheDefaultTeamLosesItsLastCore(){
        place("core-shard", 10, 10, Team.SHARDED);
        assertEquals(List.of(), ticks(3));
        assertFalse(WaveState.of(world).gameOver);
        world.buildings().remove(world.buildings().at(10, 10), Cause.SERVER);
        List<WorldEvent> over = ticks(3);
        assertEquals(1, over.size(), over.toString());
        assertEquals(Team.CRUX, ((GameEvent.GameOver)over.getFirst()).winner());
        assertTrue(WaveState.of(world).gameOver);
    }

    @Test
    void attackEndsWhenOneTeamIsLeft(){
        rules.attackMode = true;
        rules.waves = false;
        place("core-shard", 10, 10, Team.SHARDED);
        place("core-shard", 30, 30, Team.CRUX);
        assertEquals(List.of(), ticks(3));
        world.buildings().remove(world.buildings().at(30, 30), Cause.SERVER);
        List<WorldEvent> over = ticks(3);
        assertEquals(1, over.size(), over.toString());
        assertEquals(Team.SHARDED, ((GameEvent.GameOver)over.getFirst()).winner());
    }

    @Test
    void wavesEndAtTheWinWaveOnceNoEnemyIsLeft(){
        place("core-shard", 10, 10, Team.SHARDED);
        rules.waves = true;
        rules.waveTimer = false;
        rules.winWave = 5;
        WaveState.of(world).wave = 4;
        assertEquals(List.of(), ticks(3));
        WaveState.of(world).wave = 5;
        List<WorldEvent> over = ticks(3);
        assertEquals(1, over.size(), over.toString());
        assertEquals(Team.SHARDED, ((GameEvent.GameOver)over.getFirst()).winner());
    }

    @Test
    void aWorldWithoutAGameNeverEndsOne(){
        for(int i = 0; i < 3; i++) world.tick();
        assertEquals(List.of(), events.drain());
        assertFalse(WaveState.of(world).gameOver);
    }

    @Test
    void theEditorAndCanGameOverKeepTheGameGoing(){
        rules.editor = true;
        assertEquals(List.of(), ticks(3));
        rules.editor = false;
        rules.canGameOver = false;
        assertEquals(List.of(), ticks(3));
        assertFalse(WaveState.of(world).gameOver);
    }
}
