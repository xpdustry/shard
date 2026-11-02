package com.xpdustry.shard.sim.team;

import com.xpdustry.shard.sim.blocks.storage.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * One team's data (vanilla {@code Teams.TeamData}): its live cores in registration order, its last known core, the
 * teams it counts as enemies, and its rebuild plans ({@link BlockPlan}, construction's). The rest of vanilla's
 * {@code TeamData} lives with its owners: unit lists and counts in {@link TeamUnits}, unit grids in the spatial index,
 * building lists in the building index. The base builder and RTS AI come with their packages.
 */
public final class TeamData implements Checkpointable{
    int team;
    final List<Core> cores = new ArrayList<>();
    Core lastCore;
    int[] coreEnemies = {};
    /** {@code plans}: the team's rebuild plans, the newest first. */
    final List<BlockPlan> plans = new ArrayList<>();

    /** A blank team's data for a checkpoint to fill. */
    public TeamData(){
    }

    TeamData(int team){
        this.team = team;
    }

    TeamData copy(CopyContext context){
        var out = new TeamData(team);
        for(Core core : cores) out.cores.add(context.map(core));
        out.lastCore = context.map(lastCore);
        out.coreEnemies = coreEnemies.clone();
        for(BlockPlan plan : plans) out.plans.add(plan.copy());
        return out;
    }

    /** Whether there is anything to store or hash: a fresh team's data is all defaults. */
    boolean any(){
        return !cores.isEmpty() || lastCore != null || coreEnemies.length > 0 || !plans.isEmpty();
    }

    public int team(){
        return team;
    }

    /** {@code cores}: the live cores, in the order they registered. Do not modify. */
    public List<Core> cores(){
        return Collections.unmodifiableList(cores);
    }

    /** {@code lastCore}: the first core at the last team stats update, or null. */
    public Core lastCore(){
        return lastCore;
    }

    /** {@code coreEnemies}: the other active teams, in the active list's order. */
    public int[] coreEnemies(){
        return coreEnemies.clone();
    }

    /**
     * {@code plans}: the team's rebuild plans in order, the newest first (vanilla's {@code Queue}, {@code addFirst}
     * on each loss). Construction adds and drops them ({@code sim.build.RebuildPlans}); an AI that rebuilds reads them
     * and may remove the one it takes. Modify through this list only on the world thread.
     */
    public List<BlockPlan> plans(){
        return plans;
    }

    /** {@code core()}: the first core, or null. */
    public Core core(){
        return cores.isEmpty() ? null : cores.getFirst();
    }

    public boolean hasCore(){
        return !cores.isEmpty();
    }

    /** {@code isAlive()}: whether the team has a core (neoplasm's hearts aren't modelled). */
    public boolean isAlive(){
        return hasCore();
    }

    public boolean noCores(){
        return cores.isEmpty();
    }

    /**
     * {@code active()}: the wave team while waves are on, or a team with a core, a building the indexer has filed, or
     * (neoplastic) a unit. While a map loads the indexer has filed nothing yet, as vanilla's.
     */
    public boolean active(World world){
        Rules rules = UnitCalls.rules(world);
        if((team == rules.waveTeam && rules.waves) || !cores.isEmpty() || hasBuildings(world)) return true;
        if(team != Team.NEOPLASTIC) return false;
        TeamUnits units = TeamUnits.of(world);
        return units != null && units.units(team).size() > 0;
    }

    /** {@code buildings.size > 0}: any building filed under this team. */
    private boolean hasBuildings(World world){
        TileGrid tiles = world.tiles();
        boolean[] any = {false};
        world.buildings().eachInChunks(team, 0, 0, tiles.width() - 1, tiles.height() - 1, building -> any[0] = true);
        return any[0];
    }

    @Override
    public void write(CheckpointOut out){
        out.i("team", team).refs("cores", cores.toArray(Core[]::new), cores.size()).ref("lastCore", lastCore)
            .ints("coreEnemies", coreEnemies, coreEnemies.length).list("plans", plans);
    }

    @Override
    public void read(CheckpointIn in){
        team = in.i("team");
        Core[] stored = in.refs("cores", Core.class);
        if(stored == null || Arrays.asList(stored).contains(null)) throw new CheckpointException("team " + team + " has a missing core");
        cores.clear();
        cores.addAll(Arrays.asList(stored));
        lastCore = in.ref("lastCore", Core.class);
        coreEnemies = in.ints("coreEnemies");
        if(coreEnemies == null) throw new CheckpointException("team " + team + " has no enemy list");
        List<BlockPlan> storedPlans = in.list("plans", BlockPlan.class);
        if(storedPlans == null || storedPlans.contains(null)) throw new CheckpointException("team " + team + "'s rebuild plans are missing");
        plans.clear();
        plans.addAll(storedPlans);
    }

    @Override
    public String toString(){
        return "TeamData{cores=" + cores + ", team=" + Team.get(team).name() + "}";
    }
}
