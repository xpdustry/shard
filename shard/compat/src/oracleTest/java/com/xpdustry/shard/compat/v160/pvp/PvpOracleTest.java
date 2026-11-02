package com.xpdustry.shard.compat.v160.pvp;

import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.rules.*;
import mindustry.game.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate (WP83): a PvP game against vanilla, exact frame by frame for {@value #FRAMES} frames through a save
 * ({@link PvpRun}). Sharded has core A; crux has cores B and C, walls, and a battery linked to a node. With
 * {@code coreCapture} and a {@value #RADIUS}-unit {@code enemyCoreBuildRadius}:
 * <ol>
 *   <li>sharded's player joins alone and the game waits for players, paused ({@code pvpAutoPause}), until crux's
 *   joins: both sides pause and resume on the same frames, everything stands still meanwhile ({@code Time.time},
 *   {@code state.tick}, {@code state.updateId}, wave time, units), the state snapshot's paused byte matches frame by
 *   frame, and a dagger commanded just before the pause follows the control pathfinder's path across it;</li>
 *   <li>a crux builder's wall inside A's radius is refused and one outside it is built; a sharded builder's wall
 *   inside B's radius is refused;</li>
 *   <li>sharded wears B down: B is captured ({@code setTile}), and the sharded wall refused before is built there
 *   now;</li>
 *   <li>the players leave, vanilla saves, and both sides load the save and go on;</li>
 *   <li>sharded wears C down: crux's last core is captured, the game is over with sharded the winner, and the next
 *   frame destroys crux to derelict ({@code setTeams}, its units killed over the next five seconds).</li>
 * </ol>
 * Each step asserts that it happened, so a fixture where nothing does can't pass.
 */
class PvpOracleTest{
    static final int WIDTH = 96, HEIGHT = 48, FRAMES = 1300, SAVE_AT = 450;
    static final float RADIUS = 120f, WEAR = 8f;
    static final int AX = 10, AY = 24, BX = 50, BY = 36, CX = 80, CY = 12;

    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    @Test
    void aCoreIsCapturedBuildingNearEnemyCoresIsRefusedAndTheLastCoreEndsTheGame(){
        var run = new PvpRun(WIDTH, HEIGHT);
        state.rules.coreCapture = true;
        state.rules.enemyCoreBuildRadius = RADIUS;
        run.place("core-shard", AX, AY, Team.sharded);
        run.place("core-shard", BX, BY, Team.crux);
        run.place("core-shard", CX, CY, Team.crux);
        run.items(AX, AY, "copper", 500);
        run.items(BX, BY, "copper", 500);
        for(int x = 70; x < 74; x++) run.place("copper-wall", x, 20, Team.crux);
        // a field of walls: crux's building tree splits (its quadtree order counts), and one in five of them is killed
        for(int x = 58; x < 90; x++){
            for(int y = 40; y < 42; y++) run.place("copper-wall", x, y, Team.crux);
        }
        run.place("battery", 76, 22, Team.crux);
        run.place("power-node", 78, 22, Team.crux);
        run.link(78, 22, 76, 22);
        run.start();
        com.xpdustry.shard.sim.rules.Rules rules = run.shard.part(WorldRules.class).rules;
        assertTrue(rules.pvp && rules.attackMode && rules.coreCapture && rules.pvpAutoPause, "the rules came through the save");

        int sb = run.unit("alpha", Team.sharded, 44 * 8f, 30 * 8f);
        int cb = run.unit("alpha", Team.crux, 16 * 8f, 30 * 8f);
        int cd = run.unit("dagger", Team.crux, 74 * 8f, 16 * 8f);
        // a commanded dagger, ordered just before the pause: its path (the control pathfinder's) runs across it
        int md = run.commanded("dagger", Team.sharded, 20 * 8f, 6 * 8f);

        // 1: one team with a player waits, paused; the second team's player resumes the game
        run.at(1, () -> {
            run.join(Team.sharded);
            run.move(Team.sharded, 36 * 8f, 6 * 8f, md);
        });
        run.at(40, () -> run.join(Team.crux));
        // 2: a wall inside A's radius (refused), one outside (built), and sharded's inside B's (refused)
        run.at(45, () -> {
            run.build(cb, "copper-wall", 14, 30);
            run.build(cb, "copper-wall", 30, 24);
            run.build(sb, "copper-wall", 46, 32);
        });
        // 3: sharded wears B down until it is captured
        for(int f = 60; f < 320; f++){
            run.at(f, () -> {
                if(world.build(BX, BY) != null && world.build(BX, BY).team == Team.crux) run.damage(BX, BY, Team.sharded, WEAR);
            });
        }
        run.at(320, () -> run.build(sb, "copper-wall", 46, 32));
        // 4: the players leave before the save, which doesn't hold them, and the waiting stops
        run.at(420, () -> {
            run.leave(Team.sharded);
            run.leave(Team.crux);
            state.rules.pvpAutoPause = false;
            run.shard.part(WorldRules.class).rules.pvpAutoPause = false;
        });

        // through the pause and after it: every frame's state (Time.time, state.tick, wave time, enemies, every
        // building and unit) is compared by runTo, and here each frame's state snapshot paused byte on both sides
        String frozenAt2 = null;
        long firstUpdateId = state.updateId;
        for(int f = 1; f <= 70; f++){
            run.runTo(f);
            run.stateSnapshot();
            String clock = "time " + arc.util.Time.time + " tick " + state.tick + " wavetime " + state.wavetime + " units " + run.vanillaState().stream()
                .filter(line -> line.startsWith("unit ")).toList();
            if(f == 2) frozenAt2 = clock;
            if(f > 2 && f < 40) assertEquals(frozenAt2, clock, "frame " + f + ": frozen while paused");
            if(f == 45) assertNotEquals(frozenAt2, clock, "running again");
        }
        assertEquals(run.vanillaPausedBytes, run.shardPausedBytes, "the state snapshot's paused byte, frame by frame");
        var expected = new ArrayList<String>();
        for(int f = 1; f <= 70; f++) expected.add(f + " " + (f < 40));
        assertEquals(expected, run.shardPausedBytes, "paused from the end of frame 1 (sharded alone) to frame 40 (crux joins)");
        assertEquals(39, run.pausedFrames);
        // frames 2 to 40 run paused: 1 ends with the pause decided, 40 begins paused and ends with crux's player in
        assertEquals(39, run.frame - (state.updateId - firstUpdateId), "state.updateId stood still for the 39 paused frames");

        run.runTo(SAVE_AT);
        var dagger = run.vanillaUnit(md);
        assertTrue(dagger.within(36 * 8f, 6 * 8f, 16f), "the commanded dagger walked its path across the pause: " + dagger.x + "," + dagger.y);
        assertEquals("core-shard t1", run.vanillaBlock(BX, BY), "B was captured");
        assertEquals("air", run.vanillaBlock(14, 30), "the crux wall inside A's radius was refused");
        assertEquals("copper-wall t2", run.vanillaBlock(30, 24), "the crux wall outside it was built");
        assertEquals("copper-wall t1", run.vanillaBlock(46, 32), "sharded's wall by B, refused before the capture, was built after it");
        assertEquals(2, run.calls.get("BeginPlace"), run.calls::toString);
        assertTrue(run.calls.getOrDefault("SetTile", 0) >= 1, run.calls::toString);
        assertTrue(run.calls.getOrDefault("PlayerSpawn", 0) >= 2, run.calls::toString);
        assertEquals(List.of(), run.vanillaOvers, "crux still has C");

        // 5: after the save, sharded wears C down; crux loses its last core
        run.saveAndLoad();
        for(int f = SAVE_AT + 50; f < 900; f++){
            run.at(f, () -> {
                if(world.build(CX, CY) != null && world.build(CX, CY).team == Team.crux) run.damage(CX, CY, Team.sharded, WEAR);
            });
        }
        run.runTo(FRAMES);

        assertEquals("core-shard t1", run.vanillaBlock(CX, CY), "C was captured");
        assertEquals(1, run.vanillaOvers.size(), "one game over: " + run.vanillaOvers);
        assertTrue(run.vanillaOvers.getFirst().endsWith(" " + Team.sharded.id), "sharded won: " + run.vanillaOvers);
        assertTrue(state.gameOver);
        assertTrue(run.calls.getOrDefault("SetTeams", 0) >= 1, "crux was destroyed to derelict: " + run.calls);
        for(int x = 70; x < 74; x++){
            String wall = run.vanillaBlock(x, 20);
            assertTrue(wall.equals("air") || wall.equals("copper-wall t0"), "crux's wall at " + x + ": " + wall);
        }
        int killed = 0, derelict = 0;
        for(int x = 58; x < 90; x++){
            for(int y = 40; y < 42; y++){
                String wall = run.vanillaBlock(x, y);
                if(wall.equals("air")) killed++;
                else if(wall.equals("copper-wall t0")) derelict++;
            }
        }
        assertEquals(64, killed + derelict, "the field is derelict or killed");
        assertTrue(killed > 0 && derelict > 0, "some of the field was killed: " + killed + " of 64");
        assertNull(run.vanillaUnit(cd), "crux's dagger was killed");
        assertNull(run.vanillaUnit(cb), "crux's builder was killed");
        assertNotNull(run.vanillaUnit(sb), "sharded's builder lives");
        assertTrue(run.calls.getOrDefault("UnitDeath", 0) + run.calls.getOrDefault("UnitDestroy", 0) >= 2, run.calls::toString);
    }
}
