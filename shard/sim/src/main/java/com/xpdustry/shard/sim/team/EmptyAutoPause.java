package com.xpdustry.shard.sim.team;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * The dedicated server's empty-world pause, {@code ServerControl}'s {@code Trigger.update} listener
 * (ServerControl.java:320-335), per world. {@code Trigger.update} fires first in {@code Logic.update}, after the
 * frame's posted packets (joins and leaves) and before its {@code isPaused} check: here, last in
 * {@link Phase#COMMANDS}, after admission, so a pause or resume it makes holds for the rest of the same tick
 * ({@link World#frozen()}). Once a game is being played ({@code state.isGame()}, {@link Teams#playing()}):
 * <ul>
 *   <li>with {@code autoPause} ({@link Teams#autoPause()}): no player in the world pauses it and marks it auto-paused;
 *   a player resumes it only if it was auto-paused, so a pause made by hand survives;</li>
 *   <li>without: a world still auto-paused and paused is resumed (the config was turned off).</li>
 * </ul>
 * Its flag ({@link Teams#autoPaused()}) is apart from {@link PvpAutoPause}'s, as vanilla's are; the console's
 * {@code pause} clears it, and a new map starts with it clear ({@code ResetEvent}). Vanilla re-pauses an empty world
 * every frame; setting the flag again changes nothing, so this does the same.
 */
@Register
public final class EmptyAutoPause implements PhaseHandler{
    @Override
    public Phase phase(){
        return Phase.COMMANDS;
    }

    /** After admission and every other command handler. */
    @Override
    public int order(){
        return Integer.MAX_VALUE;
    }

    @Override
    public void run(World world){
        Teams teams = Teams.of(world);
        if(teams == null || !teams.playing()) return;
        if(teams.autoPause){
            Players players = Players.of(world);
            if(players == null || players.list().size() == 0){
                teams.autoPaused = true;
                world.paused(true);
            }else if(teams.autoPaused){
                teams.autoPaused = false;
                world.paused(false);
            }
        }else if(teams.autoPaused && world.paused()){
            // unpause when the config is disabled
            world.paused(false);
            teams.autoPaused = false;
        }
    }
}
