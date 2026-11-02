package com.xpdustry.shard.compat.v160.sync;

import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * {@code state.teams.present}, which the state and block snapshots walk: every team, in id order, that has a building
 * or a unit, or is the wave team while waves are on ({@code Teams.java:182-249}, {@code TeamData.active()}). Cores and
 * neoplastic units make a team active too, but a team with either already has a building or a unit.
 *
 * <p>Vanilla computes it at the start of each update ({@code updateTeamStats}); this reads it at the snapshot, after
 * the tick, so a team that got its first building or lost its last one during the tick counts as it is now. The
 * teams owner (WP23) keeps the list as world state; read it from there once it does.
 */
final class TeamPresence{
    static final int TEAMS = 256;

    private TeamPresence(){
    }

    /** The present teams' ids, ascending. */
    static IntSeq present(World world){
        var present = new boolean[TEAMS];
        world.buildings().each(building -> present[building.team & 0xff] = true);
        ActiveList<Unit> units = world.units();
        for(int i = 0; i < units.size(); i++) present[units.get(i).team & 0xff] = true;
        Rules rules = world.part(WorldRules.class).rules;
        if(rules.waves) present[rules.waveTeam & 0xff] = true;
        var out = new IntSeq();
        for(int team = 0; team < TEAMS; team++) if(present[team]) out.add(team);
        return out;
    }
}
