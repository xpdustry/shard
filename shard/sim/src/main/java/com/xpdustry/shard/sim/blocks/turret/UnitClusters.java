package com.xpdustry.shard.sim.blocks.turret;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code TeamData.getClustered} (Teams.java:477), which {@code UnitSorts.grouped} ranks ripples' targets by:
 * per team, how many of its ground units stood in each 70-unit square when it was last counted. A count is refreshed
 * on a read more than 10 frames of {@code Time.time} after the last one, from {@code TeamData.units}
 * ({@link TeamUnits#units(int)}, as of the last {@code updateTeamStats}).
 *
 * <p>{@link Persistence#AUTHORITATIVE}: the counts are stale by design between refreshes.
 */
@Register
public final class UnitClusters implements WorldPart, Checkpointable{
    /** {@code clusterChunkSize}. */
    static final float CHUNK = 70f;

    /** Per team, null until it is first read. */
    private Cluster[] teams = new Cluster[TeamUnits.TEAMS];

    /** The world's clusters, or null in a world built without them. */
    public static UnitClusters of(World world){
        return world.find(UnitClusters.class);
    }

    /** {@code team.data().getClustered(x, y)}. */
    public int getClustered(World world, int team, float x, float y){
        Cluster cluster = teams[team];
        if(cluster == null) cluster = teams[team] = new Cluster();
        float time = world.timers().time();
        // update based on ticks passed (no increment)
        if(time > cluster.lastUpdate + 10f){
            cluster.lastUpdate = time;
            cluster.counts.clear();
            UnitSeq units = TeamUnits.of(world).units(team);
            for(int i = 0; i < units.size(); i++){
                Unit u = units.get(i);
                // clusters are for artillery, which can't hit flying units
                if(!u.isFlying()) cluster.counts.increment(key(u.x, u.y));
            }
        }
        return cluster.counts.get(key(x, y));
    }

    static int key(float x, float y){
        return ((Mathf.floor(x / CHUNK) & 0xFFF) << 12) | (Mathf.floor(y / CHUNK) & 0xFFF);
    }

    @Override
    public WorldPart copy(CopyContext context){
        var out = new UnitClusters();
        for(int team = 0; team < teams.length; team++){
            if(teams[team] != null) out.teams[team] = teams[team].copy();
        }
        return out;
    }

    @Override
    public void hash(Hasher hasher){
        for(int team = 0; team < teams.length; team++){
            Cluster cluster = teams[team];
            if(cluster == null) continue;
            hasher.i(team).f(cluster.lastUpdate);
            int[] keys = cluster.sortedKeys();
            hasher.i(keys.length);
            for(int key : keys) hasher.i(key).i(cluster.counts.get(key));
        }
    }

    /** One team's clusters, as a checkpoint row: keys ascending, so equal counts write equal bytes. */
    private record Row(int team, float lastUpdate, int[] keys, int[] counts){
    }

    @Override
    public void write(CheckpointOut out){
        var rows = new ArrayList<Row>();
        for(int team = 0; team < teams.length; team++){
            Cluster cluster = teams[team];
            if(cluster == null) continue;
            int[] keys = cluster.sortedKeys(), counts = new int[keys.length];
            for(int i = 0; i < keys.length; i++) counts[i] = cluster.counts.get(keys[i]);
            rows.add(new Row(team, cluster.lastUpdate, keys, counts));
        }
        out.list("teams", rows);
    }

    @Override
    public void read(CheckpointIn in){
        teams = new Cluster[TeamUnits.TEAMS];
        for(Row row : in.list("teams", Row.class)){
            if(row.team() < 0 || row.team() >= teams.length || row.keys().length != row.counts().length){
                throw new CheckpointException("a unit cluster row is corrupt");
            }
            var cluster = new Cluster();
            cluster.lastUpdate = row.lastUpdate();
            for(int i = 0; i < row.keys().length; i++) cluster.counts.put(row.keys()[i], row.counts()[i]);
            teams[row.team()] = cluster;
        }
    }

    private static final class Cluster{
        /** {@code lastClusterUpdateTimer}. */
        float lastUpdate = -100f;
        /** {@code clusteredCounts}. */
        final IntIntMap counts = new IntIntMap();

        Cluster copy(){
            var out = new Cluster();
            out.lastUpdate = lastUpdate;
            out.counts.putAll(counts);
            return out;
        }

        int[] sortedKeys(){
            var keys = new IntSeq();
            counts.each((key, value) -> keys.add(key));
            int[] out = keys.toArray();
            Arrays.sort(out);
            return out;
        }
    }
}
