package com.xpdustry.shard.sim.team;

import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.waves.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WP83: vanilla's PvP auto-pause ({@code NetServer.update}, NetServer.java:1056-1069; {@code isWaitingForPlayers},
 * :1030), case by case. The decision is taken at the end of a tick ({@code SYNC}), so the next tick is the first paused
 * or running one.
 */
class PvpAutoPauseTest{
    World world;
    Rules rules;
    Teams teams;

    @BeforeEach
    void world(){
        world = UnitWorlds.world(60, 60, 4);
        rules = world.part(WorldRules.class).rules;
        rules.waves = false;
        rules.pvp = true;
        rules.attackMode = true;
        teams = Teams.of(world);
        teams.play();
        world.buildings().setBlock(10, 10, world.content().block("core-shard"), Team.SHARDED, 0, Cause.SERVER);
        world.buildings().setBlock(45, 45, world.content().block("core-shard"), Team.CRUX, 0, Cause.SERVER);
    }

    Player player(int team){
        Player player = Player.create(world);
        player.name = "p" + team;
        player.team(team);
        player.add();
        return player;
    }

    @Test
    void fewerThanTwoTeamsWithPlayersPauses(){
        assertTrue(PvpAutoPause.waitingForPlayers(world));
        world.tick();
        assertTrue(world.paused(), "no players: paused");
        player(Team.SHARDED);
        player(Team.SHARDED);
        world.tick();
        assertTrue(world.paused(), "one team with players");
        Player crux = player(Team.CRUX);
        assertFalse(PvpAutoPause.waitingForPlayers(world));
        world.tick();
        assertFalse(world.paused(), "two teams: resumed");
        assertFalse(teams.pvpAutoPaused());
        crux.remove();
        world.tick();
        assertTrue(world.paused(), "crux left");
        assertTrue(teams.pvpAutoPaused());
    }

    @Test
    void aManualPauseSurvivesOnceTheAutoPauseHasLifted(){
        player(Team.SHARDED);
        world.tick();
        assertTrue(world.paused(), "auto-paused");
        player(Team.CRUX);
        world.tick();
        assertFalse(world.paused());
        assertFalse(teams.pvpAutoPaused(), "only a lifted auto-pause clears the flag");
        world.paused(true);
        for(int i = 0; i < 5; i++) world.tick();
        assertTrue(world.paused(), "paused by hand, not auto-paused: it stays");
    }

    /** Vanilla quirk: {@code pvpAutoPaused} starts true, so the first update lifts a pause made by hand. */
    @Test
    void aManualPauseBeforeAnyAutoPauseIsLifted(){
        player(Team.SHARDED);
        player(Team.CRUX);
        assertTrue(teams.pvpAutoPaused(), "starts true");
        world.paused(true);
        world.tick();
        assertFalse(world.paused());
    }

    @Test
    void notInAGameOverOutsidePvpOrWithTheRuleOff(){
        WaveState.of(world).gameOver = true;
        assertFalse(PvpAutoPause.waitingForPlayers(world));
        world.tick();
        assertFalse(world.paused(), "the game is over");
        WaveState.of(world).gameOver = false;
        rules.pvpAutoPause = false;
        world.tick();
        assertFalse(world.paused(), "the rule is off");
        rules.pvpAutoPause = true;
        rules.pvp = false;
        world.tick();
        assertFalse(world.paused(), "not PvP");
    }

    @Test
    void aWorldWithoutAGameIsNeverPaused(){
        World fresh = UnitWorlds.world(30, 30, 4);
        fresh.part(WorldRules.class).rules.pvp = true;
        fresh.tick();
        assertFalse(fresh.paused(), "state.isGame() is false before play()");
    }
}
