package com.xpdustry.shard.compat.v160.command;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every stance (UnitStance.java:95-112), exact against vanilla frame by frame ({@link CommandRun}): {@code holdfire},
 * {@code pursuetarget}, {@code stop}, {@code patrol}, {@code ram}, {@code boost}. {@code holdposition}, {@code mineauto}
 * and the item stances belong to the commands that allow them ({@code SupportCommandsOracleTest}).
 */
class StancesOracleTest{
    static final int W = 64, H = 48, ENEMY = 2;

    static CommandRun.Cell walled(int x, int y){
        return PayloadCommandsOracleTest.walled(x, y);
    }

    @Test
    void holdFirePursueAndStop(){
        try(var run = new CommandRun(W, H, StancesOracleTest::walled)){
            int t = run.team;
            int target = run.dummy("fortress", ENEMY, 22 * 8f, 30 * 8f);
            int[] holding = {run.spawn("dagger", t, 16 * 8f, 34 * 8f), run.spawn("dagger", t, 16 * 8f, 36 * 8f)};
            int[] chasing = {run.spawn("dagger", t, 8 * 8f, 10 * 8f), run.spawn("mace", t, 9 * 8f, 12 * 8f)};
            int[] firing = {run.spawn("dagger", t, 16 * 8f, 26 * 8f), run.spawn("dagger", t, 16 * 8f, 24 * 8f)};
            run.stance(2, "holdfire", true, holding);
            run.stance(2, "pursuetarget", true, chasing);
            // the chasers walk toward the fortress until they see it, then pursue it on their own
            run.move(3, 20 * 8f, 25 * 8f, false, chasing);
            // stop: orders gone (the holders' new move), the stances stay
            run.move(300, 10 * 8f, 44 * 8f, false, holding);
            run.stance(400, "stop", true, holding);
            run.stance(450, "holdfire", false, holding);
            boolean[] pursued = {false};
            run.also = at -> {
                for(int id : chasing) if(run.unit(id) != null && run.ai(id).attackTarget != null) pursued[0] = true;
            };
            run.at(401, () -> {
                for(int id : holding) assertNull(run.ai(id).targetPos, "stop cleared the move");
            });
            run.at(440, () -> {
                for(int id : holding) assertEquals(0, run.unit(id).mounts[0].totalShots, "held fire");
            });
            run.run(700);
            assertTrue(pursued[0], "the chasers pursued the fortress on their own");
            assertTrue(run.unit(firing[0]).mounts[0].totalShots > 0, "the others fired");
            assertTrue(run.unit(holding[0]).mounts[0].totalShots > 0, "and so did the holders once released");
        }
    }

    @Test
    void patrolBetweenTwoPointsAndRam(){
        try(var run = new CommandRun(W, H, StancesOracleTest::walled)){
            int t = run.team;
            run.dummy("dagger", ENEMY, 26 * 8f, 40 * 8f);
            int[] patrol = {run.spawn("dagger", t, 8 * 8f, 40 * 8f), run.spawn("flare", t, 8 * 8f, 42 * 8f)};
            run.stance(2, "patrol", true, patrol);
            run.move(3, 20 * 8f, 40 * 8f, false, patrol);
            run.move(4, 8 * 8f, 34 * 8f, true, patrol);

            int ram = run.dummy("mace", ENEMY, 20 * 8f, 10 * 8f);
            int[] rams = {run.spawn("dagger", t, 8 * 8f, 10 * 8f), run.spawn("dagger", t, 8 * 8f, 12 * 8f)};
            run.stance(2, "ram", true, rams);
            run.attack(3, ram, false, rams);
            run.at(300, () -> assertTrue(run.ai(patrol[0]).queue.length > 0, "patrol keeps its points in the queue"));
            run.saveAt(350);
            run.run(800);
            assertTrue(run.unit(ram) == null || run.unit(ram).health < run.unit(ram).maxHealth, "the rammers hit the mace");
            assertTrue(run.seen("call UnitDeath") >= 1, "the fight cost lives: " + run.seen);
        }
    }

    @Test
    void boostOverAWall(){
        try(var run = new CommandRun(W, H, StancesOracleTest::walled)){
            int t = run.team;
            int[] novas = {run.spawn("nova", t, 10 * 8f, 10 * 8f), run.spawn("nova", t, 10 * 8f, 13 * 8f)};
            run.stance(2, "boost", true, novas);
            run.move(3, 50 * 8f, 10 * 8f, false, novas);
            run.at(200, () -> assertTrue(run.unit(novas[0]).elevation > 0.5f, "boosting"));
            run.stance(500, "boost", false, novas);
            run.run(700);
            assertTrue(run.unit(novas[0]).x > 33 * 8f, "flew over the wall");
        }
    }
}
