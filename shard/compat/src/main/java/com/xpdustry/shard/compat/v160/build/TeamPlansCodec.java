package com.xpdustry.shard.compat.v160.build;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.compat.v160.entity.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.ContentType;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.*;

import java.util.*;

/**
 * Team rebuild plans in a save and a world stream ({@code SaveVersion.writeTeamBlocks}/{@code readTeamBlocks}), from
 * and into the world's {@link TeamData#plans()}. A loaded file's plans become the teams' ({@link #load}); a written
 * one holds the active teams' plans, then sharded's when it isn't active, as vanilla writes them ({@link #project}).
 *
 * <p>Configs cross as {@code TypeIO} objects through {@link WirePlans}. A config the sim can't hold (a building,
 * unit, team, tech node or logic sensor reference, none of which a block's {@code config()} gives) is kept as null.
 */
public final class TeamPlansCodec{
    private TeamPlansCodec(){
    }

    /**
     * {@code readTeamBlocks}'s result, already parsed by the reader ({@code MapState.plans}: a team listed twice keeps
     * its last plans, the first plan per tile counts), into the teams of {@code world}. Content ids are the file's.
     */
    public static void load(World world, BuildingCodecs codecs, List<MapState.TeamPlans> plans, ContentHeader.IdMapping ids){
        Teams teams = Teams.of(world);
        if(teams == null) return;
        var context = new FileWireContext(world, codecs, ids);
        WirePlans.ContentIds contentIds = WirePlans.ContentIds.of(ids);
        for(MapState.TeamPlans team : plans){
            List<BlockPlan> list = teams.get(team.team()).plans();
            list.clear();
            for(MapState.TeamPlan plan : team.plans()){
                int block = Math.max(0, ids.toCurrent(ContentType.BLOCK, plan.block()));
                Object config = config(WirePlans.value(WireObjects.readObject(new Reads(plan.config()), context), contentIds));
                list.addLast(new BlockPlan(plan.x(), plan.y(), plan.rotation(), block, config));
            }
        }
    }

    /**
     * {@code writeTeamBlocks}' teams: {@code state.teams.getActive()}, then sharded if it isn't among them, each with
     * its plans in order and content in {@code ids}' stream ids. The active list is read without dropping the teams
     * that stopped being active, which vanilla's {@code getActive()} does as it writes; the written list is the same.
     */
    public static List<MapState.TeamPlans> project(World world, ContentHeader.IdMapping ids){
        Teams teams = Teams.of(world);
        if(teams == null) return List.of();
        var order = new ArrayList<Integer>();
        int[] active = teams.activeTeams().toArray();
        for(int team : active) if(teams.get(team).active(world)) order.add(team);
        if(!order.contains(VanillaIds.SHARDED)) order.add(VanillaIds.SHARDED);
        WirePlans.ContentIds contentIds = WirePlans.ContentIds.of(ids);
        var out = new ArrayList<MapState.TeamPlans>(order.size());
        for(int team : order){
            var list = new ArrayList<MapState.TeamPlan>();
            for(BlockPlan plan : teams.get(team).plans()){
                var config = new Writes(8);
                WireObjects.writeObject(config, WirePlans.config(plan.config, contentIds));
                list.add(new MapState.TeamPlan((short)plan.x, (short)plan.y, (short)plan.rotation, ids.requireStream(ContentType.BLOCK, plan.block),
                    config.toByteArray()));
            }
            out.add(new MapState.TeamPlans(team, List.copyOf(list)));
        }
        return List.copyOf(out);
    }

    /** A config the sim holds, or null for a reference to something only a live world has. */
    private static Object config(Object value){
        try{
            BuildPlan.writeConfig(value, new Writes(8));
            return value;
        }catch(RuntimeException e){
            return null;
        }
    }
}
