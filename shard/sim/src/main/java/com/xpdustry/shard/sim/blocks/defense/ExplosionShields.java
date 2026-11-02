package com.xpdustry.shard.sim.blocks.defense;

import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.combat.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code ExplosionShield} check (Damage.java:112, UnitComp.java:926): before an explosion wave or a flying
 * wreck's crash damages anything, the first enemy shield of {@code team} ({@code indexer.getEnemy(team,
 * BlockFlag.shield)}) whose shield covers the point absorbs it. Only the force projector is an {@code ExplosionShield}.
 */
public final class ExplosionShields{
    private ExplosionShields(){
    }

    /** Whether a shield took the hit; an explosion that spares no team ({@link Targets#ANY_TEAM}) is never absorbed. */
    public static boolean absorbed(World world, int team, float x, float y, float damage){
        if(team == Targets.ANY_TEAM) return false;
        for(Building b : world.buildings().enemy(team, BlockFlag.shield)){
            if(b instanceof ForceProjector shield && shield.absorbExplosion(x, y, damage)) return true;
        }
        return false;
    }
}
