package com.xpdustry.shard.sim.waves;

import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.rules.Rules;
import com.xpdustry.shard.sim.rules.WorldRules;
import com.xpdustry.shard.sim.unit.Unit;
import com.xpdustry.shard.sim.world.*;
import arc.struct.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.gen.*;
import org.junit.jupiter.api.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate (WP47): the countdown against vanilla's {@code Logic.update}, frame by frame for 1,500 frames on a stone world
 * with one spawn: waves every 120 ticks held by {@code waitEnemies} while the wave's daggers live, released when they
 * die, a skipped wave, the countdown running with enemies alive once {@code waitEnemies} is off, then held again by the
 * win wave. Each frame the wave, countdown and enemy count equal vanilla's.
 */
class CountdownOracleTest{
    @Test
    void theCountdownWaitsAndSkipsAsVanillas(){
        Vanilla.emptyWorld(40, 40);
        mindustry.Vars.world.tile(20, 20).setOverlay(Blocks.spawn);
        state.rules.waves = true;
        state.rules.waveTimer = true;
        state.rules.waveSpacing = 120f;
        state.rules.waitEnemies = true;
        state.rules.spawns = Seq.with(new SpawnGroup(UnitTypes.dagger){{
            unitScaling = 2f;
        }});
        state.wavetime = 90f;
        World world = VanillaWaves.world();
        Rules rules = world.part(WorldRules.class).rules;
        WaveState waves = WaveState.of(world);

        int heldFrames = 0, runningWithEnemies = 0;
        for(int frame = 0; frame < 1500; frame++){
            switch(frame){
                case 400 -> {
                    for(mindustry.gen.Unit unit : Groups.unit.copy(new Seq<>())) unit.kill();
                    for(Unit unit : world.units().toList()) unit.kill();
                }
                case 700 -> {
                    logic.skipWave();
                    Waves.skipWave(world);
                }
                case 900 -> state.rules.waitEnemies = rules.waitEnemies = false;
                case 1200 -> {
                    state.rules.winWave = state.wave;
                    rules.winWave = waves.wave;
                }
                default -> {}
            }
            VanillaWaves.sync(world);
            Vanilla.frame();
            world.tick();
            String at = "frame " + frame;
            assertEquals(state.wave, waves.wave, at + ": wave");
            assertEquals(state.wavetime, waves.wavetime, at + ": wavetime");
            assertEquals(state.enemies, waves.enemies, at + ": enemies");
            assertEquals(logic.isWaitingWave(), waves.isWaitingWave(rules), at + ": waiting");
            if(logic.isWaitingWave()) heldFrames++;
            else if(state.enemies > 0) runningWithEnemies++;
        }
        assertTrue(heldFrames > 300, "the countdown was held: " + heldFrames);
        assertTrue(runningWithEnemies > 100, "the countdown ran with enemies alive: " + runningWithEnemies);
        assertTrue(state.wave > 5, "waves ran: " + state.wave);
    }
}
