package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's unit cap (Units.java:115-125): the one place Shard decides whether a team may have another unit of a type.
 * Unit factories and reconstructors, a dumped unit payload, the unit cargo loader's placement, the spawn ability and
 * mlog's {@code spawn} all ask {@link #canCreate}; a unit joining the world over its cap dies through
 * {@link TeamUnits}, which reads {@link #getCap}.
 */
public final class Units{
    private Units(){
    }

    /**
     * {@code Units.getCap(team)}: none for the wave team outside PvP (and always in the campaign), with
     * {@code disableUnitCap}, and for a team that ignores it ({@code ignoreUnitCap}: neoplastic); else
     * {@code unitCap}, plus the team's buildings' {@code unitCapModifier} sum ({@code TeamData.unitCap}) when
     * {@code unitCapVariable}, never below zero.
     */
    public static int getCap(World world, int team){
        Rules rules = UnitCalls.rules(world);
        if((team == rules.waveTeam && !rules.pvp) || (UnitCalls.isCampaign(rules) && team == rules.waveTeam) || rules.disableUnitCap
            || team == Team.NEOPLASTIC){
            return Integer.MAX_VALUE;
        }
        if(!rules.unitCapVariable) return Math.max(0, rules.unitCap);
        TeamUnits counts = TeamUnits.of(world);
        return Math.max(0, rules.unitCap + (counts == null ? 0 : counts.capModifier(world, team)));
    }

    /**
     * {@code Units.canCreate(team, type)}: a type that ignores the cap ({@code useUnitCap} false) always may; any other
     * only while {@code team} has fewer of it than {@link #getCap} and the rules don't ban it. The count is per type
     * ({@code countType}), as vanilla's is.
     */
    public static boolean canCreate(World world, int team, UnitDef type){
        if(!world.unitTable().params(type).useUnitCap()) return true;
        TeamUnits counts = TeamUnits.of(world);
        int count = counts == null ? 0 : counts.count(team, type);
        return count < getCap(world, team) && !UnitCalls.rules(world).isUnitBanned(type.id());
    }

    /** {@link #canCreate(World, int, UnitDef)} by unit type id. */
    public static boolean canCreate(World world, int team, int type){
        return canCreate(world, team, world.content().unit(type));
    }
}
