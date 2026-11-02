package com.xpdustry.shard.sim.game;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.waves.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code Logic.checkGameState} (Logic.java:327) outside the campaign, last in {@code Logic.update}: the game
 * is over, once, and {@link GameEvent.GameOver} published,
 * <ul>
 *   <li>without attack mode, when the default team has no core left: the wave team wins;</li>
 *   <li>in attack mode, when at most one active team other than derelict is alive, or, outside pvp, the default team
 *   has no core: the first such team in the active order wins, or derelict;</li>
 *   <li>otherwise, with waves on and a win wave reached, when no enemy is left and no wave is spawning: the default
 *   team wins.</li>
 * </ul>
 * It runs once a game was started on the world ({@link Play}, {@code state.isGame()}), while {@code rules.canGameOver},
 * outside the editor ({@code runStateCheck}; a headless server's map is never invalid). {@code state.gameOver} is {@link WaveState#gameOver}, which the wave countdown reads. Not ported: the
 * campaign (never hosted), and pvp's check while waiting for players, which is the server's.
 */
@Register
public final class CheckGameState implements PhaseHandler{
    @Override
    public Phase phase(){
        return Phase.GAME_STATE;
    }

    @Override
    public void run(World world){
        Rules rules = UnitCalls.rules(world);
        if(rules.editor || !rules.canGameOver || UnitCalls.isCampaign(rules)) return;
        WaveState state = WaveState.of(world);
        Teams teams = Teams.of(world);
        if(state == null || teams == null || !teams.playing()) return;
        if(!rules.attackMode && teams.playerCores(world).isEmpty() && !state.gameOver){
            over(world, state, rules.waveTeam);
        }else if(rules.attackMode){
            // count # of teams alive
            IntSeq active = teams.getActive(world);
            int alive = 0, left = -1;
            for(int i = 0; i < active.size; i++){
                int team = active.get(i);
                if(team == Team.DERELICT || !teams.get(team).isAlive()) continue;
                alive++;
                if(left < 0) left = team;
            }
            if((alive <= 1 || (!rules.pvp && teams.core(rules.defaultTeam) == null)) && !state.gameOver){
                over(world, state, left < 0 ? Team.DERELICT : left);
            }
        }else if(!state.gameOver && rules.waves && state.enemies == 0 && rules.winWave > 0 && state.wave >= rules.winWave && !spawning(world)){
            over(world, state, rules.defaultTeam);
        }
    }

    private static boolean spawning(World world){
        WaveSpawner spawner = WaveSpawner.of(world);
        return spawner != null && spawner.isSpawning();
    }

    private static void over(World world, WaveState state, int winner){
        state.gameOver = true;
        world.publish(new GameEvent.GameOver(world.ticks(), winner));
    }
}
