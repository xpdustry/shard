package com.xpdustry.shard.sim.blocks.power;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code Groups.powerGraph}: the added power graphs (their updaters), in update order, with
 * {@code EntityGroup}'s swap-remove semantics. {@link Persistence#AUTHORITATIVE}: the order, and each added graph's
 * state through the hash; each graph's position in the list is rebuilt.
 */
@Register
public final class PowerGraphs implements WorldPart, Checkpointable{
    private final ActiveList<PowerGraph> list;

    public PowerGraphs(){
        list = new ActiveList<>(PowerGraph.SLOT);
    }

    private PowerGraphs(PowerGraphs source, CopyContext context){
        list = source.list.copy(context);
    }

    /** The world's power graph group, or null in a world built without it: its graphs never update. */
    public static PowerGraphs of(World world){
        return world.find(PowerGraphs.class);
    }

    /** {@code Groups.powerGraph}. */
    public ActiveList<PowerGraph> list(){
        return list;
    }

    @Override
    public WorldPart copy(CopyContext context){
        return new PowerGraphs(this, context);
    }

    @Override
    public void hash(Hasher hasher){
        hasher.i(list.size());
        for(int i = 0; i < list.size(); i++) list.get(i).hashState(hasher);
    }

    @Override
    public void write(CheckpointOut out){
        out.refs("list", list.toList().toArray(PowerGraph[]::new), list.size());
    }

    @Override
    public void read(CheckpointIn in){
        PowerGraph[] stored = in.refs("list", PowerGraph.class);
        if(stored == null || Arrays.asList(stored).contains(null)) throw new CheckpointException("Groups.powerGraph holds a missing graph");
        list.restore(Arrays.asList(stored));
    }
}
