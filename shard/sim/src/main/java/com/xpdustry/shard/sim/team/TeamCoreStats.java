package com.xpdustry.shard.sim.team;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.world.*;

/**
 * The cores' part of vanilla's {@code Teams.updateTeamStats}, first thing in {@code Logic.update}: each team's last core
 * is its first. Runs in {@code TEAMS} after the unit part ({@code TeamStats}).
 */
@Register
public final class TeamCoreStats implements PhaseHandler{
    @Override
    public Phase phase(){
        return Phase.TEAMS;
    }

    @Override
    public int order(){
        return -90;
    }

    @Override
    public void run(World world){
        Teams teams = Teams.of(world);
        if(teams != null) teams.updateStats();
    }
}
