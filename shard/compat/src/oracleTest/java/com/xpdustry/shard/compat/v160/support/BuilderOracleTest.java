package com.xpdustry.shard.compat.v160.support;

import com.xpdustry.shard.sim.ai.*;
import com.xpdustry.shard.sim.build.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.unit.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L1 for {@code BuilderAI} (WP70) through W2.15's construction: polys on Ground Zero, hosted for survival by vanilla
 * and Shard, compared frame by frame ({@link SupportRun}), Calls included. Each poly runs the {@code rebuild} command's
 * AI.
 * <ul>
 *   <li>Walls by the core are destroyed: each leaves a rebuild plan, which the polys take one by one (every two
 *   seconds, front to back) and build again from the core's copper.</li>
 *   <li>A player builds two lancers from its client's queue: the poly follows the player and builds the player's plan
 *   with it, as the same plan object.</li>
 * </ul>
 */
class BuilderOracleTest{
    @Test
    void polysRebuildDestroyedWalls(){
        var run = new SupportRun("serpulo/groundZero");
        int cx = run.coreX, cy = run.coreY;
        run.items("copper", 500);
        int[][] walls = {{cx - 4, cy + 4}, {cx - 3, cy + 4}, {cx - 2, cy + 4}, {cx + 3, cy - 4}, {cx + 4, cy - 4}};
        run.at(1, () -> {
            for(int[] wall : walls) run.place("copper-wall", wall[0], wall[1], 0);
            run.spawn("poly", cx * 8f + 30f, cy * 8f, null, 0);
            run.spawn("poly", cx * 8f - 30f, cy * 8f - 8f, null, 0);
        });
        run.at(3, () -> {
            for(int[] wall : walls) run.damage(wall[0], wall[1], 2f);
        });
        int[] planned = {0};
        int last = run.until(4_000, () -> {
            if(run.frame == 3) planned[0] = Teams.of(run.shard).get(run.rules.defaultTeam).plans().size();
            if(run.frame < 10) return false;
            for(int[] wall : walls){
                var b = run.ours(wall[0], wall[1]);
                if(b == null || b instanceof ConstructBuild || !b.block().name().equals("copper-wall")) return false;
            }
            return Teams.of(run.shard).get(run.rules.defaultTeam).plans().isEmpty();
        });
        run.run(120);
        System.out.println("polys rebuilt " + walls.length + " walls in " + last + " frames; Calls " + run.calls);
        assertEquals(walls.length, planned[0], "each destroyed wall left a rebuild plan");
        assertEquals(walls.length, run.calls.getOrDefault("BuildDestroyed", 0), "the walls were destroyed: " + run.calls);
        assertEquals(walls.length, run.calls.getOrDefault("ConstructFinish", 0), "the walls were built again: " + run.calls);
    }

    @Test
    void aPolyHelpsAPlayerBuild(){
        var run = new SupportRun("serpulo/groundZero");
        int cx = run.coreX, cy = run.coreY;
        run.items("copper", 1000);
        run.items("lead", 1000);
        run.items("silicon", 1000);
        run.items("titanium", 1000);
        run.withPlayer = true;
        run.plan("lancer", cx + 4, cy + 5, 0);
        run.plan("lancer", cx - 4, cy + 5, 0);
        // after the join, whose player draws an id at its admission
        run.at(2, () -> run.spawn("poly", cx * 8f, cy * 8f - 40f, null, 0));
        boolean[] followed = {false}, built = {false};
        run.until(6_000, () -> {
            for(int i = 0; i < run.shard.units().size(); i++){
                Unit unit = run.shard.units().get(i);
                if(unit.controller instanceof CommandAi ai && ai.commandController instanceof BuilderAi builder && builder.following != null){
                    followed[0] = true;
                    // the leader's own plan object, worked on (a site's lastBuilder only names players' units)
                    if(Builder.activelyBuilding(unit) && unit.plans.first() == Builder.buildPlan(builder.following)) built[0] = true;
                }
            }
            return run.calls.getOrDefault("ConstructFinish", 0) >= 2;
        });
        run.run(120);
        System.out.println("a poly helped: followed " + followed[0] + ", built " + built[0] + "; Calls " + run.calls);
        assertTrue(followed[0], "the poly followed the player");
        assertTrue(built[0], "the poly built the player's site");
        assertEquals(2, run.calls.getOrDefault("ConstructFinish", 0), "both lancers stand: " + run.calls);
    }
}
