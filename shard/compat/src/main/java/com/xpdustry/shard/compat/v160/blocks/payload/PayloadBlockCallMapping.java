package com.xpdustry.shard.compat.v160.blocks.payload;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.sync.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.sim.blocks.payload.*;
import com.xpdustry.shard.sim.building.*;

/**
 * The payload blocks' Calls ({@link PayloadBlockEvent}): {@code Synced} is {@code NetServer.syncBuilding(build)}, one
 * {@code blockSnapshot} of the building to everyone, as it is after the tick. A building gone by then sends nothing.
 */
@Register
public final class PayloadBlockCallMapping implements CallMapping<PayloadBlockEvent>{
    @Override
    public Class<PayloadBlockEvent> event(){
        return PayloadBlockEvent.class;
    }

    @Override
    public void map(PayloadBlockEvent event, CallContext context, CallSink sink){
        switch(event){
            case PayloadBlockEvent.Synced synced -> {
                Building building = context.world().buildings().at(synced.tile());
                if(building == null || building.tile() != synced.tile()) return;
                sink.all(new BlockSnapshots(context.world().content(), BuildingCodecs.builtIn()).one(building));
            }
        }
    }
}
