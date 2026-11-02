package com.xpdustry.shard.compat.v160.sync;

import com.xpdustry.shard.compat.v160.entity.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.sim.world.*;

/** What a {@link CallMapping} may read and queue besides its event: on the world's thread, between ticks. */
public interface CallContext{
    /** The world after the tick that published the event. Read it; never change it. */
    World world();

    /** The entity id of {@code session}'s player in this world, or -1: how a Call names its sender. */
    int player(long session);

    /** Health sync: the building whose origin is the tile {@code pos} goes out in the next health batch. */
    void healthChanged(int pos);

    /** How entities are written, for Calls that carry one ({@code unitSpawn}): the world's content and building codecs. */
    default EntityProjector projector(){
        return new EntityProjector(world().content(), BuildingCodecs.builtIn());
    }
}
