package com.xpdustry.shard.sim.blocks.distribution;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code Pools.get(ContainerStat.class)}: the free {@link ContainerStat}s unloaders reuse, a stack of at most
 * {@link #MAX} (Arc's {@code Pool}: {@code obtain} pops the last freed, {@code free} pushes and resets). Vanilla's
 * pool is one static per JVM; Shard keeps one per world, which is what a vanilla server hosting that world alone
 * has. It is gameplay: a reused stat keeps its {@code lastUsed}, which orders an unloader's neighbours.
 */
@Register
public final class ContainerStats implements WorldPart, Checkpointable{
    /** {@code Pools.get}'s default maximum. */
    public static final int MAX = 5000;

    private ArrayList<ContainerStat> free = new ArrayList<>();

    /** The world's pool, or null in a world built without it (then every stat is new). */
    public static ContainerStats of(World world){
        return world.find(ContainerStats.class);
    }

    /** {@code Pool.obtain()}. */
    public ContainerStat obtain(){
        return free.isEmpty() ? new ContainerStat() : free.removeLast();
    }

    /** {@code Pools.freeAll(stats, true)}: each is kept while the pool has room, and reset. */
    public void freeAll(List<ContainerStat> stats){
        for(ContainerStat stat : stats){
            if(stat == null) continue;
            if(free.size() < MAX) free.add(stat);
            stat.building = null;
        }
    }

    /** How many stats are free, for tests. */
    public int size(){
        return free.size();
    }

    @Override
    public WorldPart copy(CopyContext context){
        var out = new ContainerStats();
        for(ContainerStat stat : free) out.free.add(stat.copy(context));
        return out;
    }

    @Override
    public void hash(Hasher hasher){
        hasher.i(free.size());
        for(ContainerStat stat : free) stat.hash(hasher);
    }

    @Override
    public void write(CheckpointOut out){
        out.list("free", free);
    }

    @Override
    public void read(CheckpointIn in){
        free = new ArrayList<>(in.list("free", ContainerStat.class));
    }
}
