package com.xpdustry.shard.sim.blocks.power;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code Groups.powerGraph.update()} in {@code Logic.updateEntities} (Logic.java:486): each added graph
 * solves once, in group order, after the units and before the buildings, so the solve reads the production and demand
 * the buildings left last tick.
 */
@Register
public final class PowerUpdate implements PhaseHandler{
    @Override
    public Phase phase(){
        return Phase.POWER;
    }

    @Override
    public void run(World world){
        PowerGraphs graphs = PowerGraphs.of(world);
        if(graphs != null) graphs.list().each(PowerGraph::update);
    }
}
