package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.spatial.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code Teams.updateTeamStats}, the unit part, first thing in {@code Logic.update}: recounts
 * {@link TeamUnits} and refills the team unit grids of the {@link SpatialIndex} ({@code TeamData.unitTree}) from
 * {@code Groups.unit}. Runs in {@code TEAMS} after physics begins ({@link PhysicsBegin}), so the grids see this tick's
 * pushes. The teams owner adds the rest (presence, cores, players, bosses) around it.
 */
@Register
public final class TeamStats implements PhaseHandler{
    @Override
    public Phase phase(){
        return Phase.TEAMS;
    }

    @Override
    public int order(){
        return -100;
    }

    @Override
    public void run(World world){
        TeamUnits counts = TeamUnits.of(world);
        if(counts != null) counts.recount(world.units());
        SpatialIndex index = SpatialIndex.of(world);
        if(index != null) index.fillTeams(world.units());
    }
}
