package com.xpdustry.shard.compat.v160.build;

import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.blocks.defense.*;
import org.junit.jupiter.api.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * WP64's build tower ({@code BuildTurret}, W3.6) on {@link BuildRun}, which compares every building, site, the core's
 * items, the team plans and the Calls with vanilla after every frame, then every save chunk and the team plans' bytes:
 * a tower on nitrogen and a power source rebuilds the walls destroyed in its range from the core's copper, plan by
 * plan, and leaves the ones out of range.
 */
class BuildTowerOracleTest{
    static final int SIZE = 64;

    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    /** Nitrogen into the tower on both sides. */
    static void nitrogen(BuildRun run, int x, int y, float amount){
        run.vanilla(x, y).handleLiquid(run.vanilla(x, y), content.liquid("nitrogen"), amount);
        var ours = run.ours(x, y);
        ours.handleLiquid(ours, BuildRun.EPOCH.liquid("nitrogen").id(), amount);
    }

    String block(int x, int y){
        var build = world.build(x, y);
        return build == null ? "air" : build.block.name;
    }

    @Test
    void buildTowerRebuildsTeamPlans(){
        var run = new BuildRun(SIZE, SIZE);
        run.core("core-shard", 8, 8);
        run.items("copper", 500);
        run.items("lead", 200);
        run.place("build-tower", 30, 30, 0);
        run.place("power-source", 28, 30, 0);
        run.place("copper-wall", 36, 30, 0);
        run.place("copper-wall", 36, 32, 0);
        run.place("copper-wall-large", 30, 37, 0);
        run.place("conveyor", 24, 30, 1);
        // out of the tower's 200-unit range
        run.place("copper-wall", 60, 60, 0);
        for(int f = 1; f < 900; f += 60){
            run.at(f, () -> nitrogen(run, 30, 30, 4f));
        }
        run.at(3, () -> {
            run.kill(36, 30);
            run.kill(36, 32);
            run.kill(30, 37);
            run.kill(24, 30);
            run.kill(60, 60);
        });
        int[] building = {0};
        run.until(900, () -> {
            if(run.ours(30, 30) instanceof BuildTurret tower && tower.unit.plans.size() > 0) building[0]++;
            return false;
        });
        run.chunks();
        assertTrue(building[0] > 0, "the tower took plans");
        assertEquals("copper-wall", block(36, 30), "a wall was rebuilt");
        assertEquals("copper-wall", block(36, 32), "a second wall was rebuilt");
        assertEquals("copper-wall-large", block(30, 37), "the large wall was rebuilt");
        assertEquals("conveyor", block(24, 30), "the conveyor was rebuilt");
        assertEquals("air", block(60, 60), "the far wall stays a plan");
        assertEquals(1, state.teams.get(mindustry.game.Team.sharded).plans.size, "only the far wall is still planned");
    }
}
