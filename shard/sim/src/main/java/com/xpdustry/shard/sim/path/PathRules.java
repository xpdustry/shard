package com.xpdustry.shard.sim.path;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.sim.world.*;

/**
 * The rules the path layer reads: a copy of the vanilla {@code Rules} and {@code state} fields that pathing depends
 * on, plus Shard's own layers, which are off by default. The world's rules owner (WP46)
 * pushes a new value with {@link PathService#rules(PathRules)} when they change; it takes effect at the next flush.
 *
 * @param waves {@code rules.waves}
 * @param attackMode {@code rules.attackMode}
 * @param pvp {@code rules.pvp}
 * @param waveTeam {@code rules.waveTeam}
 * @param defaultTeam {@code rules.defaultTeam}
 * @param wave {@code state.wave}: seeds {@code randomWaveAI} target choice
 * @param coreCapture {@code rules.coreCapture}: derelict buildings pack as team 255, derelict cores are enemies
 * @param randomWaveAI {@code rules.randomWaveAI}: the wave team also targets one random building flag
 * @param limitMapArea {@code rules.limitMapArea}, with the four limits below: RTS costs outside it are impassable for
 *     teams that aren't AI ({@code ControlPathfinder.cost})
 * @param sizeAwarePaths Shard: the {@link PathCost#GROUND_LARGE} class adds a soft clearance cost (note 22 §2.5)
 * @param threatPaths Shard: wave-AI fields add {@value Threat#COST} per enemy turret covering a tile (note 22 §2.9)
 * @param rtsPopBudget Shard: queue pops spent on RTS field builds per tick, in request order
 */
public record PathRules(boolean waves, boolean attackMode, boolean pvp, int waveTeam, int defaultTeam, int wave,
                        boolean coreCapture, boolean randomWaveAI, boolean limitMapArea, int limitX, int limitY, int limitWidth, int limitHeight,
                        boolean sizeAwarePaths, boolean threatPaths, int rtsPopBudget){
    /** Vanilla's defaults ({@code new Rules()}, {@code state.wave = 1}), Shard's layers off. */
    public static final PathRules DEFAULT = new PathRules(false, false, false, 2, 1, 1, false, false,
        false, 0, 0, 1, 1, false, false, 200_000);

    public PathRules{
        if(waveTeam < 0 || waveTeam > 255 || defaultTeam < 0 || defaultTeam > 255) throw new IllegalArgumentException("team ids are 0..255");
        if(rtsPopBudget < 1) throw new IllegalArgumentException("rtsPopBudget must be positive");
    }

    /** {@code Team.isAI()} ({@code Team.java:112}), without the campaign case a server never has. */
    public boolean isAI(int team){
        return (waves || attackMode) && team != defaultTeam && !pvp;
    }

    public PathRules withWaves(boolean waves){
        return new PathRules(waves, attackMode, pvp, waveTeam, defaultTeam, wave, coreCapture, randomWaveAI,
            limitMapArea, limitX, limitY, limitWidth, limitHeight, sizeAwarePaths, threatPaths, rtsPopBudget);
    }

    public PathRules withWave(int wave){
        return new PathRules(waves, attackMode, pvp, waveTeam, defaultTeam, wave, coreCapture, randomWaveAI,
            limitMapArea, limitX, limitY, limitWidth, limitHeight, sizeAwarePaths, threatPaths, rtsPopBudget);
    }

    public PathRules withRandomWaveAI(boolean randomWaveAI){
        return new PathRules(waves, attackMode, pvp, waveTeam, defaultTeam, wave, coreCapture, randomWaveAI,
            limitMapArea, limitX, limitY, limitWidth, limitHeight, sizeAwarePaths, threatPaths, rtsPopBudget);
    }

    public PathRules withCoreCapture(boolean coreCapture){
        return new PathRules(waves, attackMode, pvp, waveTeam, defaultTeam, wave, coreCapture, randomWaveAI,
            limitMapArea, limitX, limitY, limitWidth, limitHeight, sizeAwarePaths, threatPaths, rtsPopBudget);
    }

    public PathRules withLimitMapArea(int x, int y, int width, int height){
        return new PathRules(waves, attackMode, pvp, waveTeam, defaultTeam, wave, coreCapture, randomWaveAI,
            true, x, y, width, height, sizeAwarePaths, threatPaths, rtsPopBudget);
    }

    public PathRules withSizeAwarePaths(boolean sizeAwarePaths){
        return new PathRules(waves, attackMode, pvp, waveTeam, defaultTeam, wave, coreCapture, randomWaveAI,
            limitMapArea, limitX, limitY, limitWidth, limitHeight, sizeAwarePaths, threatPaths, rtsPopBudget);
    }

    public PathRules withThreatPaths(boolean threatPaths){
        return new PathRules(waves, attackMode, pvp, waveTeam, defaultTeam, wave, coreCapture, randomWaveAI,
            limitMapArea, limitX, limitY, limitWidth, limitHeight, sizeAwarePaths, threatPaths, rtsPopBudget);
    }

    public PathRules withRtsPopBudget(int rtsPopBudget){
        return new PathRules(waves, attackMode, pvp, waveTeam, defaultTeam, wave, coreCapture, randomWaveAI,
            limitMapArea, limitX, limitY, limitWidth, limitHeight, sizeAwarePaths, threatPaths, rtsPopBudget);
    }

    void hash(Hasher hasher){
        hasher.bool(waves).bool(attackMode).bool(pvp).i(waveTeam).i(defaultTeam).i(wave).bool(coreCapture)
            .bool(randomWaveAI).bool(limitMapArea).i(limitX).i(limitY).i(limitWidth)
            .i(limitHeight).bool(sizeAwarePaths).bool(threatPaths).i(rtsPopBudget);
    }

    void write(Writes out){
        out.bool(waves);
        out.bool(attackMode);
        out.bool(pvp);
        out.b(waveTeam);
        out.b(defaultTeam);
        out.i(wave);
        out.bool(coreCapture);
        out.bool(randomWaveAI);
        out.bool(limitMapArea);
        out.i(limitX);
        out.i(limitY);
        out.i(limitWidth);
        out.i(limitHeight);
        out.bool(sizeAwarePaths);
        out.bool(threatPaths);
        out.i(rtsPopBudget);
    }

    static PathRules read(Reads in){
        return new PathRules(in.bool(), in.bool(), in.bool(), in.ub(), in.ub(), in.i(), in.bool(), in.bool(),
            in.bool(), in.i(), in.i(), in.i(), in.i(), in.bool(), in.bool(), in.i());
    }
}
