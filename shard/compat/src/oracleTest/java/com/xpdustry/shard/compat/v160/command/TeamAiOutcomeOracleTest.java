package com.xpdustry.shard.compat.v160.command;

import com.xpdustry.shard.sim.build.ConstructBuild;
import com.xpdustry.shard.sim.building.Building;
import com.xpdustry.shard.sim.waves.WaveState;
import mindustry.game.Team;
import org.junit.jupiter.api.*;

import static com.xpdustry.shard.compat.v160.command.TeamAiRun.*;
import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The team AIs under unmodified scheduling ({@link TeamAiRun#unpinned}, as W4.10's {@code MoveOutcomeOracleTest}):
 * vanilla with its pathfinder threads, its formation executor, its own physics random and the RTS AI's
 * {@code damagedSet} in identity-hash order; Shard with its production field budget; frames paced at 60 a second;
 * nothing compared frame by frame. An attack map plays to game over on both
 * sides, and the outcome is compared:
 * <ul>
 *   <li>both sides end, within {@value #MAX_FRAMES} frames, with the same winner;</li>
 *   <li>their game-over frames are at most {@value #END_WINDOW} frames apart;</li>
 *   <li>by game over each side's base builder has built at least {@value #MIN_BUILT} buildings for crux, and the
 *   counts are within a factor of {@value #BUILT_RATIO} of each other; crux's RTS AI sent squads out (units it
 *   mobilizes, flag 1) on both sides, as many within a factor of {@value #SENT_RATIO}.</li>
 * </ul>
 * The map: sharded (the default team) with a core and one ground factory, crux with a core and three, on copper; every
 * factory cheats and builds 25 times faster. Both teams run {@code rtsAi}, crux also {@code buildAi}. Crux outnumbers
 * sharded three to one, so its squads take sharded's core.
 */
class TeamAiOutcomeOracleTest{
    static final int W = 120, H = 50, MAX_FRAMES = 12_000, END_WINDOW = 1_800, MIN_BUILT = 6;
    static final float BUILT_RATIO = 2f, SENT_RATIO = 2f;

    @Test
    void anAttackMapPlaysToGameOverOnBothSides(){
        try(var run = TeamAiRun.unpinned(W, H, (x, y) -> x > 60 ? new CommandRun.Cell("stone", null, "ore-copper") : CommandRun.Cell.of("stone"))){
            run.rules(r -> {
                r.attackMode = true;
                r.canGameOver = true;
            }, r -> {
                r.attackMode = true;
                r.canGameOver = true;
            });
            for(int team : new int[]{SHARDED, CRUX}){
                run.teamRules(team, r -> {
                    r.rtsAi = true;
                    r.cheat = true;
                    r.unitBuildSpeedMultiplier = 25f;
                    r.buildAi = team == CRUX;
                    r.buildSpeedMultiplier = 10f;
                }, r -> {
                    r.rtsAi = true;
                    r.cheat = true;
                    r.unitBuildSpeedMultiplier = 25f;
                    r.buildAi = team == CRUX;
                    r.buildSpeedMultiplier = 10f;
                });
            }
            var c = run.run;
            // Shard's game-over check runs once a game was started on the world (Play)
            com.xpdustry.shard.sim.team.Teams.of(c.shard).play();
            c.place("core-shard", 12, 25, SHARDED);
            c.place("ground-factory", 20, 25, SHARDED);
            c.place("core-shard", 108, 25, CRUX);
            for(int y : new int[]{15, 25, 35}) c.place("ground-factory", 98, y, CRUX);

            int[] vanillaEnd = {-1}, shardEnd = {-1}, vanillaBuilt = {0}, shardBuilt = {0};
            int[][] vanillaSent = new int[3][], shardSent = new int[3][];
            vanillaSent[SHARDED] = new int[1];
            vanillaSent[CRUX] = new int[1];
            shardSent[SHARDED] = new int[1];
            shardSent[CRUX] = new int[1];
            String[] winners = new String[2];
            var vanillaSeen = new java.util.HashSet<Integer>();
            var shardSeen = new java.util.HashSet<Integer>();
            run.after = at -> {
                if(vanillaEnd[0] < 0){
                    for(mindustry.gen.Unit u : mindustry.gen.Groups.unit){
                        // a unit an RTS AI sent out is mobilized (flag 1)
                        if(u.flag == 1 && vanillaSeen.add(u.id)) vanillaSent[u.team.id][0]++;
                    }
                    int built = 0;
                    for(var tile : world.tiles){
                        if(tile.build != null && tile.build.tile == tile && tile.build.team == Team.crux
                            && !(tile.build instanceof mindustry.world.blocks.ConstructBlock.ConstructBuild)) built++;
                    }
                    vanillaBuilt[0] = Math.max(vanillaBuilt[0], built);
                    if(state.gameOver){
                        vanillaEnd[0] = c.frame;
                        winners[0] = Team.sharded.core() != null ? "sharded" : Team.crux.core() != null ? "crux" : "none";
                    }
                }
                if(shardEnd[0] < 0){
                    for(int i = 0; i < c.shard.units().size(); i++){
                        var u = c.shard.units().get(i);
                        if(u.flag == 1 && shardSeen.add(u.id())) shardSent[u.team][0]++;
                    }
                    int built = 0;
                    var buildings = c.shard.buildings();
                    for(int pos = 0; pos < c.shard.tiles().size(); pos++){
                        Building b = buildings.at(pos);
                        if(b != null && b.tile() == pos && b.team == CRUX && !(b instanceof ConstructBuild)) built++;
                    }
                    shardBuilt[0] = Math.max(shardBuilt[0], built);
                    if(WaveState.of(c.shard).gameOver){
                        shardEnd[0] = c.frame;
                        var teams = com.xpdustry.shard.sim.team.Teams.of(c.shard);
                        winners[1] = teams.core(SHARDED) != null ? "sharded" : teams.core(CRUX) != null ? "crux" : "none";
                    }
                }
            };
            while(c.frame < MAX_FRAMES && (vanillaEnd[0] < 0 || shardEnd[0] < 0)) c.run(60);

            System.out.printf("game over: vanilla at %d (%s), Shard at %d (%s); crux built %d / %d; squads sent sharded %d / %d, crux %d / %d%n",
                vanillaEnd[0], winners[0], shardEnd[0], winners[1], vanillaBuilt[0], shardBuilt[0], vanillaSent[SHARDED][0],
                shardSent[SHARDED][0], vanillaSent[CRUX][0], shardSent[CRUX][0]);
            assertTrue(CommandRun.controlThreadRuns(), "vanilla's control pathfinder ran on its thread");
            assertTrue(vanillaEnd[0] >= 0, "vanilla reached game over");
            assertTrue(shardEnd[0] >= 0, "Shard reached game over");
            assertEquals(winners[0], winners[1], "the winner");
            assertTrue(Math.abs(vanillaEnd[0] - shardEnd[0]) <= END_WINDOW, "game over " + vanillaEnd[0] + " vs " + shardEnd[0]);
            assertTrue(vanillaBuilt[0] >= MIN_BUILT && shardBuilt[0] >= MIN_BUILT, "crux's base builders built: " + vanillaBuilt[0] + " / " + shardBuilt[0]);
            assertTrue(Math.max(vanillaBuilt[0], shardBuilt[0]) <= BUILT_RATIO * Math.min(vanillaBuilt[0], shardBuilt[0]),
                "crux built " + vanillaBuilt[0] + " vs " + shardBuilt[0]);
            // crux, which wins, sends squads out on both sides; sharded, outnumbered, mostly defends its core (which leaves
            // its units unmobilized), so its count is reported only
            assertTrue(vanillaSent[CRUX][0] > 0 && shardSent[CRUX][0] > 0, "crux's squads went out on both sides");
            assertTrue(Math.max(vanillaSent[CRUX][0], shardSent[CRUX][0]) <= SENT_RATIO * Math.min(vanillaSent[CRUX][0], shardSent[CRUX][0]),
                "crux sent " + vanillaSent[CRUX][0] + " vs " + shardSent[CRUX][0]);
        }
    }
}
