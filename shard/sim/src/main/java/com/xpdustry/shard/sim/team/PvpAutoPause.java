package com.xpdustry.shard.sim.team;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.waves.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's PvP auto-pause, {@code NetServer.update} (NetServer.java:1056-1069), which runs after {@code Logic.update}
 * and before the frame's sync: first in {@link Phase#SYNC}, so the state snapshot sent after the tick carries it and
 * the next tick is the first paused (or running) one. Once a game is being played ({@code state.isGame()},
 * {@link Teams#playing()}), with {@code pvp} and {@code pvpAutoPause}:
 * <ul>
 *   <li>{@link #waitingForPlayers} ({@code isWaitingForPlayers}, NetServer.java:1030): not over, and fewer than two
 *   active teams have a player;</li>
 *   <li>when that differs from {@link World#paused()}: waiting pauses the world and marks it auto-paused; not waiting
 *   resumes it only if it was auto-paused, so a pause made by hand survives.</li>
 * </ul>
 * Vanilla quirk: {@code pvpAutoPaused} starts true ({@link Teams#pvpAutoPaused}), so a pause made by hand before the
 * first auto-pause, while two teams have players, is lifted at the next update.
 */
@Register
public final class PvpAutoPause implements PhaseHandler{
    @Override
    public Phase phase(){
        return Phase.SYNC;
    }

    @Override
    public int order(){
        return -1000;
    }

    @Override
    public void run(World world){
        Teams teams = Teams.of(world);
        if(teams == null || !teams.playing()) return;
        Rules rules = UnitCalls.rules(world);
        if(!rules.pvp || !rules.pvpAutoPause) return;
        boolean waiting = waitingForPlayers(world), paused = world.paused();
        if(waiting == paused) return;
        if(waiting){
            // is now waiting, enable pausing, flag it correctly
            teams.pvpAutoPaused = true;
            world.paused(true);
        }else if(teams.pvpAutoPaused){
            // no longer waiting, stop pausing
            world.paused(false);
            teams.pvpAutoPaused = false;
        }
    }

    /** {@code isWaitingForPlayers()}: in a PvP game that isn't over, fewer than two active teams have a player. */
    public static boolean waitingForPlayers(World world){
        Rules rules = UnitCalls.rules(world);
        WaveState state = WaveState.of(world);
        Teams teams = Teams.of(world);
        if(!rules.pvp || (state != null && state.gameOver) || teams == null) return false;
        Players players = Players.of(world);
        IntSeq active = teams.activeView(world);
        int used = 0;
        for(int i = 0; i < active.size; i++){
            int team = active.get(i);
            for(int p = 0; players != null && p < players.list().size(); p++){
                if(players.list().get(p).team == team){
                    used++;
                    break;
                }
            }
        }
        return used < 2;
    }
}
