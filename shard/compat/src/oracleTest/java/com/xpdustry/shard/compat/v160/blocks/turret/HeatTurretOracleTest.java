package com.xpdustry.shard.compat.v160.blocks.turret;

import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.blocks.turret.*;
import com.xpdustry.shard.sim.world.*;
import mindustry.game.*;
import mindustry.world.blocks.defense.turrets.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate (WP34, heat for WP60's turrets): an Erekir turret that needs heat ({@code heatRequirement}) takes it from the
 * heaters facing it ({@code calculateHeat}, Turret.java:544) every update, frame by frame as vanilla's
 * ({@link TurretRun}): its {@code heatReq} and side heat, and the efficiency they scale.
 */
class HeatTurretOracleTest{
    static final int SIZE = 64;

    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    /**
     * An afflict (needs 20 heat) on a power source with two electric heaters (3 heat each) facing it, so it shoots at
     * 0.3 efficiency once they warm up, then from frame 600 also a heat source, which saturates it; two fortresses stand
     * in range. Before the heaters warm up it has no heat and can't consume.
     */
    @Test
    void afflictShootsOnTooLittleHeatThenEnough(){
        try(var run = new TurretRun(SIZE)){
            run.place("afflict", 20, 20, Team.sharded);
            run.place("power-source", 23, 20, Team.sharded);
            run.place("electric-heater", 17, 19, Team.sharded);
            run.place("electric-heater", 17, 21, Team.sharded);
            run.place("power-source", 16, 19, Team.sharded);
            run.place("power-source", 16, 21, Team.sharded);
            run.walker("fortress", Team.crux, 38 * 8f, 24 * 8f, 38 * 8f, 24 * 8f);
            run.walker("fortress", Team.crux, 40 * 8f, 16 * 8f, 40 * 8f, 16 * 8f);
            var vanilla = (Turret.TurretBuild)world.build(20, 20);
            var shard = (TurretBuild)run.shard.buildings().at(20, 20);
            var efficiencies = new TreeSet<Float>();
            int[] lowHeatShots = {0};
            run.every(1, () -> {
                assertEquals(vanilla.heatReq, shard.heatReq, "frame " + run.frame + ": heatReq");
                assertEquals(Arrays.toString(vanilla.sideHeat), Arrays.toString(shard.sideHeat), "frame " + run.frame + ": side heat");
                efficiencies.add(vanilla.efficiency);
                if(run.frame == 600){
                    // a heat source below, facing up into the turret
                    world.tile(20, 18).setBlock(content.block("heat-source"), Team.sharded, 1);
                    run.shard.buildings().setBlock(20, 18, TurretRun.EPOCH.block("heat-source"), Team.sharded.id, 1, Cause.SERVER);
                    run.sameRandom("after placing the heat source");
                    lowHeatShots[0] = vanilla.totalShots;
                }
            });
            run.run(2400, run::allDead);
            assertTrue(efficiencies.contains(0f), "no heat at first: " + efficiencies);
            assertTrue(efficiencies.stream().anyMatch(e -> e > 0.2f && e < 0.5f), "too little heat: " + efficiencies);
            assertTrue(efficiencies.contains(1f), "enough heat: " + efficiencies);
            assertTrue(lowHeatShots[0] > 0, "it shot on too little heat");
            assertTrue(run.shots > lowHeatShots[0], "and on enough");
        }
    }
}
