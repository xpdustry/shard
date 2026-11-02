package com.xpdustry.shard.compat.v160.blocks.defense;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.sync.*;
import com.xpdustry.shard.sim.blocks.defense.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.wire.v160_7.*;

/** The walls' and doors' own Calls ({@link DefenseEvent}), to everyone: {@code autoDoorToggle(tile, open)}. */
@Register
public final class DefenseCallMapping implements CallMapping<DefenseEvent>{
    @Override
    public Class<DefenseEvent> event(){
        return DefenseEvent.class;
    }

    @Override
    public void map(DefenseEvent event, CallContext context, CallSink sink){
        TileGrid tiles = context.world().tiles();
        int pos = Point2.pack(tiles.x(event.pos()), tiles.y(event.pos()));
        switch(event){
            case DefenseEvent.AutoDoorToggled toggled -> sink.all(new Packets.AutoDoorToggle(pos, toggled.open()));
        }
    }
}
