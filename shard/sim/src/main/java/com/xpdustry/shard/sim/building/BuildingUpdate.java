package com.xpdustry.shard.sim.building;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.world.*;

/** Vanilla's {@code Groups.build.update()}: every building in the update list, in its order. */
@Register
public final class BuildingUpdate implements PhaseHandler{
    @Override
    public Phase phase(){
        return Phase.BUILDINGS;
    }

    @Override
    public void run(World world){
        world.builds().each(Building::update);
    }
}
