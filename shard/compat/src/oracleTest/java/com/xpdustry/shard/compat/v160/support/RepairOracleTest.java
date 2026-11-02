package com.xpdustry.shard.compat.v160.support;

import com.xpdustry.shard.sim.ai.*;
import com.xpdustry.shard.sim.unit.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L1 for {@code RepairAI} (WP70): megas on Ground Zero, hosted for survival by vanilla and Shard, compared frame by
 * frame ({@link SupportRun}), Calls included. Walls and a router by the core lose most of their health; each mega runs
 * the {@code repair} command's AI, picks the damaged building closest to it every 15 frames, circles it and heals it
 * with its weapons' bolts, until none is damaged.
 */
class RepairOracleTest{
    @Test
    void megasRepairDamagedBuildings(){
        var run = new SupportRun("serpulo/groundZero");
        int cx = run.coreX, cy = run.coreY;
        int[][] hurt = {{cx - 5, cy + 5}, {cx - 4, cy + 5}, {cx + 5, cy - 5}, {cx + 6, cy + 4}};
        run.at(1, () -> {
            run.place("copper-wall", hurt[0][0], hurt[0][1], 0);
            run.place("copper-wall", hurt[1][0], hurt[1][1], 0);
            run.place("titanium-wall", hurt[2][0], hurt[2][1], 0);
            run.place("router", hurt[3][0], hurt[3][1], 0);
            run.spawn("mega", cx * 8f + 40f, cy * 8f + 40f, null, 0);
            run.spawn("mega", cx * 8f - 40f, cy * 8f - 24f, null, 0);
        });
        run.at(2, () -> {
            for(int[] at : hurt) run.damage(at[0], at[1], 0.7f);
        });
        boolean[] targeted = {false};
        int last = run.until(5_000, () -> {
            for(int i = 0; i < run.shard.units().size(); i++){
                if(run.shard.units().get(i).controller instanceof CommandAi ai && ai.commandController instanceof RepairAi repair && repair.damagedTarget != null){
                    targeted[0] = true;
                }
            }
            if(run.frame < 5) return false;
            for(int[] at : hurt) if(run.ours(at[0], at[1]).damaged()) return false;
            return true;
        });
        run.run(120);
        System.out.println("megas repaired " + hurt.length + " buildings in " + last + " frames; Calls " + run.calls);
        assertTrue(targeted[0], "a mega went for a damaged building");
        assertTrue(last > 30, "the repair took the megas a while: " + last);
    }
}
