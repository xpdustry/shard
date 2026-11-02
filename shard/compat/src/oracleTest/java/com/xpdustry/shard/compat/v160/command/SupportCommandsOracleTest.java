package com.xpdustry.shard.compat.v160.command;

import com.xpdustry.shard.sim.ai.command.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The commands that run their own AI ({@code repair}, {@code rebuild}, {@code assist}, {@code mine}) and their stances
 * ({@code holdposition}, {@code mineauto}, item stances), switched by {@code setUnitCommand} and
 * {@code setUnitStance} as a player does, exact against vanilla frame by frame ({@link CommandRun}): the controller
 * made at each switch (its timer draws), stances dropped when the new command doesn't allow them, the support AIs'
 * behaviour (held to vanilla by W3's oracles) under command. Then an AI team's commanded units give up on an
 * unreachable building ({@code unreachableBuildings}, {@code isPathImpassable}).
 */
class SupportCommandsOracleTest{
    static final int W = 64, H = 48, ENEMY = 2;

    /** Stone, copper ore in a patch at x 20-24, y 30-34, and the walled map's wall. */
    static CommandRun.Cell ores(int x, int y){
        if(x >= 20 && x <= 24 && y >= 30 && y <= 34) return new CommandRun.Cell("stone", null, "ore-copper");
        return PayloadCommandsOracleTest.walled(x, y);
    }

    @Test
    void repairRebuildAssistAndMineUnderCommand(){
        try(var run = new CommandRun(W, H, SupportCommandsOracleTest::ores)){
            int t = run.team;
            run.place("core-shard", 10, 20, t);
            run.give(10, 20, "copper", 500);
            run.place("copper-wall", 16, 12, t);
            run.place("copper-wall", 17, 12, t);
            run.damage(16, 12, 0.6f);
            int mega = run.spawn("mega", t, 14 * 8f, 20 * 8f), poly = run.spawn("poly", t, 12 * 8f, 24 * 8f);
            // the mega starts on repair, its default; holdposition keeps it where it is
            run.stance(2, "holdposition", true, mega);
            run.stance(150, "holdposition", false, mega);
            // the poly mines: mineauto first, then copper only, which drops mineauto
            run.command(2, "mine", poly);
            run.stance(200, "item-copper", true, poly);
            run.at(201, () -> {
                var table = UnitStances.of(run.shard.content());
                assertFalse(run.ai(poly).hasStance(table.mineAuto), "item stances exclude mineauto");
            });
            // patrol on the mega, then rebuild: rebuild drops patrol (incompatible)
            run.command(250, "move", mega);
            run.stance(251, "patrol", true, mega);
            run.command(300, "rebuild", mega);
            run.at(301, () -> assertFalse(run.ai(mega).hasStance(UnitStances.of(run.shard.content()).patrol), "rebuild dropped patrol"));
            // a wall destroyed: the poly, set to rebuild, puts it back
            run.at(320, () -> run.damage(17, 12, 1f));
            run.command(330, "rebuild", poly);
            run.command(500, "assist", mega);
            run.saveAt(560);
            run.run(900);
            assertTrue(run.seen("order setUnitCommand mine") == 1 && run.seen("order setUnitCommand assist") == 1);
        }
    }

    /** The walled map with an island at (50, 30): a deep-water moat 3 tiles wide around ground at x 47-53, y 27-33. */
    static CommandRun.Cell island(int x, int y){
        int dx = Math.abs(x - 50), dy = Math.abs(y - 30), d = Math.max(dx, dy);
        if(d >= 4 && d <= 6) return CommandRun.Cell.of("deep-water");
        return PayloadCommandsOracleTest.walled(x, y);
    }

    @Test
    void anAiTeamGivesUpOnAnUnreachableBuilding(){
        try(var run = new CommandRun(W, H, SupportCommandsOracleTest::island)){
            // the player's core on an island; crux is the wave team, so an AI team, and its raiders walk
            run.place("core-shard", 50, 30, run.team);
            int[] raiders = {run.commanded("dagger", ENEMY, 10 * 8f, 24 * 8f), run.commanded("mace", ENEMY, 10 * 8f, 22 * 8f),
                run.commanded("dagger", ENEMY, 12 * 8f, 26 * 8f)};
            // as a team AI orders them: the core, across water ground units can't cross
            run.at(2, () -> {
                var theirs = mindustry.Vars.world.build(50, 30);
                var ours = run.shard.buildings().at(50, 30);
                for(int id : raiders){
                    run.vanillaAi(id).commandTarget(theirs);
                    run.ai(id).commandTarget(ours);
                }
            });
            run.run(700);
            boolean recorded = false;
            for(int id : raiders) recorded |= run.ai(id).unreachableBuildings.size > 0;
            assertTrue(recorded, "a raider recorded the core as unreachable");
        }
    }
}
