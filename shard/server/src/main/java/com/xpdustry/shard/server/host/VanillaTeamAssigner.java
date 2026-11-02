package com.xpdustry.shard.server.host;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's default {@code netServer.assigner} (NetServer.java:57-75). Outside PvP: the rules' default team. In PvP:
 * among the active teams ({@code state.teams.getActive()}, which drops the teams that stopped being active), the one
 * whose count of {@code seats} on it, {@code joining} not counted, is lowest. A team doesn't qualify (its value is
 * {@code Integer.MAX_VALUE}) if it is the wave team while waves are on, has no core, is derelict or doesn't protect its
 * cores. Vanilla quirk (Q233): each qualifying team's count draws {@code Mathf.random(-0.1f, 0.1f)} from the world
 * random to break ties, and {@code Seq.min} keeps the last of equal values, so with every team disqualified the last
 * active team still wins. Only an empty active list gives {@link #NONE} (vanilla's {@code null}).
 */
@Register
public final class VanillaTeamAssigner implements TeamAssigner{
    @Override
    public int assign(World world, Seat joining, List<Seat> seats){
        Rules rules = world.part(WorldRules.class).rules;
        if(!rules.pvp) return rules.defaultTeam;
        Teams teams = Teams.of(world);
        if(teams == null) return NONE;
        IntSeq active = teams.getActive(world);
        int best = NONE;
        float min = Float.MAX_VALUE;
        for(int i = 0; i < active.size; i++){
            int team = active.get(i);
            float value;
            if((rules.waveTeam == team && rules.waves) || teams.cores(team).isEmpty() || team == Team.DERELICT || !rules.teams.get(team).protectCores){
                value = Integer.MAX_VALUE;
            }else{
                int count = 0;
                for(Seat other : seats){
                    if(other.team() == team && other.session() != joining.session()) count++;
                }
                // if several have the same player count, pick at random
                value = count + Mathf.random(world.rand(), -0.1f, 0.1f);
            }
            if(value <= min){
                best = team;
                min = value;
            }
        }
        return best;
    }
}
