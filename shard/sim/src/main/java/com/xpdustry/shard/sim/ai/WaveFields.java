package com.xpdustry.shard.sim.ai;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.sim.path.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.waves.*;
import com.xpdustry.shard.sim.world.*;

/**
 * The flowfields vanilla's {@code Pathfinder} preloads when a map loads ({@code Pathfinder.java:123}): the wave team's
 * ground field, and its naval field when a spawn is on liquid, when the wave team needs fields ({@code isAI()} without
 * RTS AI). Without them a world's first wave units would wait a tick for their field. Shard takes the references at
 * the world's first {@code PATH_FLUSH}, before the flush builds new fields, and holds them for the world's life, as
 * vanilla's cache does. Deviation: vanilla decides under the map file's rules, during the load; Shard under the
 * game's, which hosting has applied by then.
 *
 * <p>A host takes them earlier, between the load and the first tick ({@link #preloadNow}), and settles the path
 * service again, so the fields are built off the tick threads: on archipelago that build was 30-45 ms of the first
 * tick.
 */
@Register
public final class WaveFields implements WorldPart, Checkpointable{
    /** Whether the first flush took the references. */
    public boolean loaded;

    public WaveFields(){
    }

    /**
     * The first flush's preload, now: a host calls it after {@code PathService.settle} and before the world's first
     * tick, then settles again to build the fields. The first {@code PATH_FLUSH} then finds the references taken. Only
     * that tick's commands come between, and the fields are repaired over what they change exactly as a build over it
     * would make them ({@code RepairTest}).
     */
    public static void preloadNow(World world){
        WaveFields fields = world.find(WaveFields.class);
        if(fields != null) fields.preload(world);
    }

    void preload(World world){
        if(loaded) return;
        loaded = true;
        PathService paths = world.find(PathService.class);
        if(paths == null || !paths.active()) return;
        Rules rules = UnitCalls.rules(world);
        int team = rules.waveTeam;
        if(!UnitCalls.isAI(world, team) || rules.teams.get(team).rtsAi) return;
        paths.field(team, PathCost.GROUND);
        // preload water on naval maps
        WaveSpawner spawner = WaveSpawner.of(world);
        if(spawner == null) return;
        TileGrid tiles = world.tiles();
        IntSeq spawns = spawner.spawns(world);
        for(int i = 0; i < spawns.size; i++){
            if(tiles.isLiquid(spawns.get(i))){
                paths.field(team, PathCost.NAVAL);
                return;
            }
        }
    }

    /** The preload, first in {@code PATH_FLUSH}. */
    @Register
    public static final class Preload implements PhaseHandler{
        @Override
        public Phase phase(){
            return Phase.PATH_FLUSH;
        }

        @Override
        public int order(){
            return -10;
        }

        @Override
        public void run(World world){
            WaveFields fields = world.find(WaveFields.class);
            if(fields != null) fields.preload(world);
        }
    }

    @Override
    public WorldPart copy(CopyContext context){
        var out = new WaveFields();
        out.loaded = loaded;
        return out;
    }

    @Override
    public void hash(Hasher hasher){
        hasher.bool(loaded);
    }

    @Override
    public void write(CheckpointOut out){
        out.bool("loaded", loaded);
    }

    @Override
    public void read(CheckpointIn in){
        loaded = in.bool("loaded");
    }
}
