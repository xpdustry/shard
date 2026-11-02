package com.xpdustry.shard.sim.waves;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * The wave part of {@code Logic.update} (Logic.java:593), after the weather and the objectives: the spawner takes the
 * tick's overlay changes, then, with waves and the wave timer on and the game not over, the countdown runs down
 * unless it is waiting for enemies; at zero, with waves on, the wave runs ({@link Waves#runWave}).
 */
@Register
public final class WaveCountdown implements PhaseHandler{
    @Override
    public Phase phase(){
        return Phase.WAVES;
    }

    @Override
    public void run(World world){
        WaveSpawner spawner = WaveSpawner.of(world);
        if(spawner != null) spawner.tileChanges(world);
        WaveState state = WaveState.of(world);
        if(state == null) return;
        Rules rules = UnitCalls.rules(world);
        if(rules.waves && rules.waveTimer && !state.gameOver && !state.isWaitingWave(rules)){
            state.wavetime = Math.max(state.wavetime - 1f, 0);
        }
        if(state.wavetime <= 0 && rules.waves) Waves.runWave(world);
    }
}
