package com.xpdustry.shard.sim.team;

import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The dedicated server's empty-world pause ({@link EmptyAutoPause}, ServerControl.java:320-335), case by case. It is
 * decided after the tick's inputs and before the tick's pause check, so the tick that sees the world empty (or a
 * player in it) is already paused (or running), as vanilla's {@code Trigger.update} precedes {@code Logic.update}'s
 * {@code isPaused} check in the same frame.
 */
class EmptyAutoPauseTest{
    World world;
    Rules rules;
    Teams teams;
    Unit dagger;

    @BeforeEach
    void world(){
        world = UnitWorlds.world(60, 60, 4);
        rules = world.part(WorldRules.class).rules;
        rules.waves = false;
        rules.pvp = false;
        teams = Teams.of(world);
        teams.play();
        world.buildings().setBlock(10, 10, world.content().block("core-shard"), Team.SHARDED, 0, Cause.SERVER);
        dagger = Unit.spawn(world, world.content().unit("dagger"), Team.CRUX, 200f, 200f, 0f);
    }

    Player player(int team){
        Player player = Player.create(world);
        player.name = "p" + team;
        player.team(team);
        player.add();
        return player;
    }

    /** Ticks once with the dagger pushed: whether it moved, i.e. whether the tick ran unpaused. */
    boolean ran(){
        dagger.vel.set(1f, 0f);
        float x = dagger.x;
        world.tick();
        return dagger.x != x;
    }

    @Test
    void offByDefaultAnEmptyWorldRuns(){
        assertFalse(teams.autoPause());
        assertTrue(ran());
        assertFalse(world.paused());
        assertFalse(teams.autoPaused());
    }

    @Test
    void anEmptyWorldPausesInTheSameTickAndAPlayerResumesIt(){
        WorldHash off = world.hash();
        teams.autoPause(true);
        assertNotEquals(off, world.hash(), "the setting is hashed while on");
        assertFalse(ran(), "the tick that sees nobody is paused");
        assertTrue(world.paused());
        assertTrue(teams.autoPaused());
        assertFalse(ran(), "still nobody");

        Player p = player(Team.SHARDED);
        assertTrue(ran(), "the tick that sees the player runs");
        assertFalse(world.paused());
        assertFalse(teams.autoPaused());

        p.remove();
        assertFalse(ran(), "the player left");
        assertTrue(teams.autoPaused());
    }

    @Test
    void aPauseMadeByHandSurvivesAJoin(){
        teams.autoPause(true);
        player(Team.SHARDED);
        assertTrue(ran());
        // the console's pause: autoPaused = false, then paused
        teams.clearAutoPaused();
        world.paused(true);
        assertFalse(ran());
        player(Team.SHARDED);
        assertFalse(ran(), "not auto-paused: a player doesn't resume it");
        assertTrue(world.paused());
    }

    @Test
    void turningItOffResumesAWorldItPaused(){
        teams.autoPause(true);
        assertFalse(ran());
        teams.autoPause(false);
        assertTrue(ran(), "the config was disabled: unpaused in the same tick");
        assertFalse(teams.autoPaused());

        // but not a pause made by hand
        world.paused(true);
        assertFalse(ran());
        assertTrue(world.paused());
    }

    @Test
    void itWaitsForAGame(){
        World fresh = UnitWorlds.world(60, 60, 4);
        Teams.of(fresh).autoPause(true);
        fresh.tick();
        assertFalse(fresh.paused(), "state.isGame() is false before logic.play()");
    }

    @Test
    void itsFlagIsApartFromPvpAutoPause(){
        rules.pvp = true;
        rules.pvpAutoPause = true;
        // crux needs a core to be an active team
        world.buildings().setBlock(45, 45, world.content().block("core-shard"), Team.CRUX, 0, Cause.SERVER);
        teams.autoPause(true);
        world.tick();
        assertTrue(world.paused());
        assertTrue(teams.autoPaused());
        assertTrue(teams.pvpAutoPaused());
        // a player of one team: the empty pause lifts at the tick's start, the PvP one pauses again at its end
        player(Team.SHARDED);
        world.tick();
        assertFalse(teams.autoPaused());
        assertTrue(world.paused(), "PvP still waits for a second team");
        assertTrue(teams.pvpAutoPaused());
        player(Team.CRUX);
        world.tick();
        assertFalse(world.paused());
        assertFalse(teams.pvpAutoPaused());
    }

    /** The console's {@code pause}, as a logged command (server.game.SetPause's body, without its server types). */
    record Pause(boolean pause) implements Command.Admin{
        @Override
        public Verdict apply(World world, Cause cause){
            Teams.of(world).clearAutoPaused();
            world.paused(pause);
            return Verdict.ADMITTED;
        }
    }

    /**
     * The three pause timings of a vanilla server frame. ServerLauncher.java:76-78 adds {@code logic} before
     * {@code netServer}; the frame first runs its posted tasks (the console's commands are posted,
     * ServerControl.java:88; so are packets), then {@code Logic.update}, which fires {@code Trigger.update}
     * (Logic.java:509, ServerControl's autoPause) before its {@code isPaused()} check (:524), then
     * {@code NetServer.update}'s PvP auto-pause (NetServer.java:1057). So:
     * <ul>
     *   <li>a console pause holds the frame it is run in (here: the tick that admits it);</li>
     *   <li>the empty-server pause holds the frame that sees nobody ({@link #anEmptyWorldPausesInTheSameTickAndAPlayerResumesIt});</li>
     *   <li>the PvP auto-pause holds from the next frame.</li>
     * </ul>
     */
    @Test
    void aConsolePauseHoldsTheTickThatAdmitsItAndPvpsTheNext(){
        assertTrue(ran());
        world.inbox().offer(new Submission(new Pause(true), new Cause.Admin("console")));
        assertFalse(ran(), "admitted this tick, paused this tick");
        assertTrue(world.paused());
        world.inbox().offer(new Submission(new Pause(false), new Cause.Admin("console")));
        assertTrue(ran(), "unpaused the tick that admits it");

        // PvP: the tick that ends waiting still runs; the next is paused
        rules.pvp = true;
        rules.pvpAutoPause = true;
        world.buildings().setBlock(45, 45, world.content().block("core-shard"), Team.CRUX, 0, Cause.SERVER);
        assertTrue(ran(), "the PvP decision comes after the tick's update");
        assertTrue(world.paused(), "decided at the tick's end");
        assertFalse(ran(), "the next tick is paused");
    }

    @Test
    void forksAndCheckpointsCarryIt(){
        teams.autoPause(true);
        world.tick();
        World restored = com.xpdustry.shard.sim.persist.Checkpoint.decode(com.xpdustry.shard.sim.persist.Checkpoint.capture(world)
            .encode(com.xpdustry.shard.sim.persist.Compression.FAST)).restore(world.config());
        World fork = world.fork();
        for(World copy : new World[]{restored, fork}){
            assertTrue(Teams.of(copy).autoPause());
            assertTrue(Teams.of(copy).autoPaused());
            assertEquals(world.hash(), copy.hash());
        }
    }
}
