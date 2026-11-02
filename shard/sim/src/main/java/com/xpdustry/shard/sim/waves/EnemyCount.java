package com.xpdustry.shard.sim.waves;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * {@code state.enemies}, the first thing {@code Logic.update} does after the posted tasks: the wave team's units of
 * {@code isEnemy} types. In {@code TEAMS}, after the posted destroys and before physics begins.
 */
@Register
public final class EnemyCount implements PhaseHandler{
    @Override
    public Phase phase(){
        return Phase.TEAMS;
    }

    @Override
    public int order(){
        return -250;
    }

    /** {@code Logic.update} does this before its {@code isPaused} check. */
    @Override
    public boolean runsWhilePaused(){
        return true;
    }

    @Override
    public void run(World world){
        WaveState state = WaveState.of(world);
        if(state == null) return;
        int waveTeam = UnitCalls.rules(world).waveTeam;
        ActiveList<Unit> units = world.units();
        int enemies = 0;
        for(int i = 0; i < units.size(); i++){
            Unit unit = units.get(i);
            if(unit.team == waveTeam && isEnemy(unit.type())) enemies++;
        }
        state.enemies = enemies;
    }

    /** {@code type.isEnemy}; hand-built test types have vanilla's default, true. */
    static boolean isEnemy(UnitDef type){
        return !type.baked() || type.isEnemy();
    }
}
