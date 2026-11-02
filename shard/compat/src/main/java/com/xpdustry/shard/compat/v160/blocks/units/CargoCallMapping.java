package com.xpdustry.shard.compat.v160.blocks.units;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.sync.*;
import com.xpdustry.shard.sim.blocks.units.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.wire.v160_7.*;

/** The cargo blocks' own Calls ({@link CargoEvent}), to everyone: {@code unitTetherBlockSpawned(tile, id)} for a cargo loader's new unit. */
@Register
public final class CargoCallMapping implements CallMapping<CargoEvent>{
    @Override
    public Class<CargoEvent> event(){
        return CargoEvent.class;
    }

    @Override
    public void map(CargoEvent event, CallContext context, CallSink sink){
        TileGrid tiles = context.world().tiles();
        int pos = Point2.pack(tiles.x(event.pos()), tiles.y(event.pos()));
        switch(event){
            case CargoEvent.TetherSpawned spawned -> sink.all(new Packets.UnitTetherBlockSpawned(pos, spawned.unit()));
        }
    }
}
