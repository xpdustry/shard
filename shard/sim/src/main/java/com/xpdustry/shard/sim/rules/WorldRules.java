package com.xpdustry.shard.sim.rules;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.path.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

/**
 * A world's {@link Rules}: authoritative state, copied by forks, fed to the world hash and stored in checkpoints.
 * Reach it with {@code world.part(WorldRules.class).rules}.
 *
 * <p>The path layer reads a copy ({@link PathRules}). Whoever sets the rules, or the wave, pushes that copy with
 * {@link #pushPathRules}: the host after loading a map, and later the rules owner (WP46) on each change. Nothing
 * pushes it per tick, so a world's paths only follow rules that were pushed.
 */
@Register
public final class WorldRules implements WorldPart, Checkpointable{
    public Rules rules = new Rules();

    /**
     * The path layer's view of {@code rules} at wave {@code wave} ({@code state.wave}). Shard's own layers
     * ({@link PathRules#sizeAwarePaths}, {@link PathRules#threatPaths}, {@link PathRules#rtsPopBudget}) aren't vanilla
     * rules: they keep {@code base}'s values.
     */
    public static PathRules pathRules(Rules rules, int wave, PathRules base){
        return new PathRules(rules.waves, rules.attackMode, rules.pvp, rules.waveTeam, rules.defaultTeam, wave, rules.coreCapture,
            rules.randomWaveAI, rules.limitMapArea, rules.limitX, rules.limitY, rules.limitWidth, rules.limitHeight,
            base.sizeAwarePaths(), base.threatPaths(), base.rtsPopBudget());
    }

    /**
     * Pushes these rules at wave {@code wave} to the world's {@link PathService}, if it runs one; they take effect at
     * its next flush. Call it after the rules or the wave change.
     */
    public void pushPathRules(World world, int wave){
        for(WorldPart part : world.parts()){
            if(part instanceof PathService paths) paths.rules(pathRules(rules, wave, paths.rules()));
        }
    }

    @Override
    public WorldPart copy(CopyContext context){
        var out = new WorldRules();
        out.rules = rules.copy();
        return out;
    }

    @Override
    public void hash(Hasher hasher){
        rules.hash(hasher);
    }

    @Override
    public void write(CheckpointOut out){
        out.obj("rules", rules);
    }

    @Override
    public void read(CheckpointIn in){
        rules = in.obj("rules", Rules.class);
        if(rules == null) throw new CheckpointException("the world's rules are missing");
    }
}
