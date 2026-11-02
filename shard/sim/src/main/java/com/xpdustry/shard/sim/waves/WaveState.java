package com.xpdustry.shard.sim.waves;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.world.*;

/**
 * The wave fields of vanilla's {@code GameState}: the wave number, the countdown, the enemy count the countdown waits
 * on, and {@code gameOver}, which stops it. A new world is at wave 1 with no countdown, as {@code new GameState()};
 * a map load sets the saved wave and countdown, and the game start sets the countdown ({@link #play}).
 *
 * <p>{@code gameOver} lives here until {@code checkGameState} is ported; whoever ends the game sets it.
 *
 * <p>{@link Persistence#AUTHORITATIVE}.
 */
@Register
public final class WaveState implements WorldPart, Checkpointable{
    /** {@code state.wave}: the next wave to spawn, counting from 1. */
    public int wave = 1;
    /** {@code state.wavetime}: ticks until the next wave. */
    public float wavetime;
    /** {@code state.enemies}: wave team units of enemy types, counted at the start of each tick. */
    public int enemies;
    /** {@code state.gameOver}. */
    public boolean gameOver;

    /** The world's wave state, or null in a world built without it. */
    public static WaveState of(World world){
        return world.find(WaveState.class);
    }

    /**
     * {@code Logic.isWaitingWave()}: the countdown is held while enemies live and the rules wait for them, or the win
     * wave is reached.
     */
    public boolean isWaitingWave(Rules rules){
        return (rules.waitEnemies || (wave >= rules.winWave && rules.winWave > 0)) && enemies > 0;
    }

    /**
     * The countdown part of {@code Logic.play()}: twice the wave spacing before the first wave, or the initial spacing
     * when set. The game-start owner calls it first, before {@code PlayEvent} ({@code Weathers.play}). Shard hosts no
     * campaign, so the difficulty's wave time multiplier doesn't apply.
     */
    public void play(Rules rules){
        wavetime = rules.initialWaveSpacing <= 0 ? rules.waveSpacing * 2 : rules.initialWaveSpacing;
    }

    @Override
    public WorldPart copy(CopyContext context){
        var out = new WaveState();
        out.wave = wave;
        out.wavetime = wavetime;
        out.enemies = enemies;
        out.gameOver = gameOver;
        return out;
    }

    @Override
    public void hash(Hasher hasher){
        hasher.i(wave).f(wavetime).i(enemies).bool(gameOver);
    }

    @Override
    public void write(CheckpointOut out){
        out.i("wave", wave).f("wavetime", wavetime).i("enemies", enemies).bool("gameOver", gameOver);
    }

    @Override
    public void read(CheckpointIn in){
        wave = in.i("wave");
        wavetime = in.f("wavetime");
        enemies = in.i("enemies");
        gameOver = in.bool("gameOver");
    }
}
