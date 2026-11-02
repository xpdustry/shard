package com.xpdustry.shard.sim.entity;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.world.*;

/** Vanilla's {@code Groups.all.update()} in {@code Logic.updateEntities}: each entity of {@link EntityGroups#all()}, in order. */
@Register
public final class MiscUpdate implements PhaseHandler{
    @Override
    public Phase phase(){
        return Phase.MISC;
    }

    @Override
    public void run(World world){
        EntityGroups groups = EntityGroups.of(world);
        if(groups != null) groups.all().each(Entity::update);
    }
}
