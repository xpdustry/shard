package com.xpdustry.shard.compat.v160.blocks.units;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.sync.*;
import com.xpdustry.shard.sim.blocks.units.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.wire.v160_7.*;

/** The unit blocks' Calls ({@link UnitBlockEvent}), to everyone: {@code unitBlockSpawn(tile)}, the tile packed. */
@Register
public final class UnitBlockCallMapping implements CallMapping<UnitBlockEvent>{
    @Override
    public Class<UnitBlockEvent> event(){
        return UnitBlockEvent.class;
    }

    @Override
    public void map(UnitBlockEvent event, CallContext context, CallSink sink){
        TileGrid tiles = context.world().tiles();
        switch(event){
            case UnitBlockEvent.Spawned spawned -> sink.all(new Packets.UnitBlockSpawn(Point2.pack(tiles.x(spawned.tile()), tiles.y(spawned.tile()))));
        }
    }
}
