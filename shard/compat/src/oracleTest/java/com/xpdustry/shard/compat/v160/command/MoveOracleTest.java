package com.xpdustry.shard.compat.v160.command;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code move} command (WP72's first gate): a group of ground, legged, naval and flying units, ordered across a
 * map with a wall and a lake, exact against vanilla frame by frame ({@link CommandRun}), through a save. The formation
 * ({@code UnitGroup}) and the control pathfinder both decide where each unit goes: the wall's two gaps make the ground
 * groups queue and give way, the risso follow the lake under the wall's end.
 */
class MoveOracleTest{
    static final int W = 96, H = 64, WALL = 48;

    /** Stone; a lake along the bottom; a wall of stone at x = 48 above the lake, with gaps at y 28-30 and 44-45. */
    static CommandRun.Cell walledLake(int x, int y){
        if(y < 12) return CommandRun.Cell.of("deep-water");
        if(y < 14) return CommandRun.Cell.of("shallow-water");
        if((x == WALL || x == WALL + 1) && !(y >= 28 && y <= 30) && !(y >= 44 && y <= 45)) return new CommandRun.Cell("stone", "stone-wall", null);
        return CommandRun.Cell.of("stone");
    }

    static int[] army(CommandRun run){
        int team = run.team;
        return new int[]{
            run.spawn("dagger", team, 10 * 8f, 30 * 8f), run.spawn("dagger", team, 12 * 8f, 32 * 8f), run.spawn("dagger", team, 10 * 8f, 34 * 8f),
            run.spawn("mace", team, 14 * 8f, 30 * 8f), run.spawn("nova", team, 14 * 8f, 36 * 8f),
            run.spawn("atrax", team, 16 * 8f, 32 * 8f), run.spawn("atrax", team, 16 * 8f, 38 * 8f),
            run.spawn("risso", team, 10 * 8f, 5 * 8f), run.spawn("risso", team, 13 * 8f, 6 * 8f),
            run.spawn("flare", team, 12 * 8f, 40 * 8f), run.spawn("flare", team, 14 * 8f, 41 * 8f), run.spawn("horizon", team, 8 * 8f, 40 * 8f)};
    }

    @Test
    void aMixedGroupMarchesAcrossTheWallThroughASave(){
        try(var run = new CommandRun(W, H, MoveOracleTest::walledLake)){
            int[] units = army(run);
            run.move(2, 76 * 8f, 16 * 8f, false, units);
            run.saveAt(500);
            run.run(1200);
            // what the fixture is for: the groups formed, and the units got there
            assertTrue(run.seen("saved and loaded") == 1, "the save ran");
            int crossed = 0;
            for(int id : units){
                var unit = run.unit(id);
                if(unit.x > (WALL + 2) * 8f) crossed++;
            }
            assertTrue(crossed >= 9, "most of the army crossed the wall: " + crossed);
        }
    }

    @Test
    void singleUnitsFinishOnOffGridPointsByTheWall(){
        try(var run = new CommandRun(W, H, MoveOracleTest::walledLake)){
            int t = run.team;
            // each alone (no group), to a point off the tile grid just past an end of the wall: the path's last
            // waypoint is a tile centre near the point, which decides whether the unit arrives or overshoots
            float[][] goals = {{(WALL + 2) * 8f + 3.3f, 27 * 8f - 2.6f}, {(WALL + 2) * 8f - 1.7f, 31 * 8f + 3.1f},
                {(WALL - 1) * 8f + 2.9f, 46 * 8f + 1.3f}, {(WALL + 3) * 8f + 3.9f, 43 * 8f - 3.7f}};
            int[] units = new int[goals.length];
            for(int i = 0; i < goals.length; i++){
                units[i] = run.spawn(i % 2 == 0 ? "dagger" : "mace", t, (12 + i * 3) * 8f, (20 + i * 8) * 8f);
                run.move(2 + i, goals[i][0], goals[i][1], false, units[i]);
            }
            run.run(900);
            for(int i = 0; i < goals.length; i++){
                assertTrue(run.unit(units[i]).within(goals[i][0], goals[i][1], 8f), "unit " + i + " arrived");
            }
        }
    }

    @Test
    void queuedOrdersAndBatchesFormOneGroup(){
        try(var run = new CommandRun(W, H, MoveOracleTest::walledLake)){
            int[] units = army(run);
            // the client's batches of 5: the formation waits for the last one
            run.batch(2, 5, 30 * 8f, 36 * 8f, units);
            run.at(3, () -> assertNotNull(run.ai(units[0]).group, "the group formed at the final batch"));
            // then a queued leg through the lower gap, and back
            run.move(240, 70 * 8f, 29 * 8f, true, units);
            run.move(241, 30 * 8f, 50 * 8f, true, units);
            run.at(239, () -> {
                // the ground and legged units (the risso stay in the lake)
                for(int i = 0; i < 7; i++) assertTrue(run.unit(units[i]).within(30 * 8f, 36 * 8f, 10 * 8f), "at the first point: " + units[i]);
            });
            run.at(300, () -> assertFalse(run.ai(units[0]).queue.length == 0, "the later legs wait in the queue"));
            run.run(900);
            assertTrue(run.seen("order commandUnits") >= 5);
            int crossed = 0;
            for(int id : units) if(run.unit(id).x > (WALL + 2) * 8f || run.unit(id).y > 46 * 8f) crossed++;
            assertTrue(crossed >= 6, "units went on through the gap: " + crossed);
        }
    }
}
