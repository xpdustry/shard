package com.xpdustry.shard.compat.v160.blocks.units;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.sync.*;
import com.xpdustry.shard.sim.blocks.units.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.wire.v160_7.*;

/**
 * The unit assembler's Calls ({@link AssemblerEvent}), to everyone: {@code assemblerDroneSpawned(tile, id)} and
 * {@code assemblerUnitSpawned(tile)}, the tile packed.
 */
@Register
public final class AssemblerCallMapping implements CallMapping<AssemblerEvent>{
    @Override
    public Class<AssemblerEvent> event(){
        return AssemblerEvent.class;
    }

    @Override
    public void map(AssemblerEvent event, CallContext context, CallSink sink){
        TileGrid tiles = context.world().tiles();
        int pos = Point2.pack(tiles.x(event.pos()), tiles.y(event.pos()));
        switch(event){
            case AssemblerEvent.DroneSpawned spawned -> sink.all(new Packets.AssemblerDroneSpawned(pos, spawned.unit()));
            case AssemblerEvent.UnitSpawned spawned -> sink.all(new Packets.AssemblerUnitSpawned(pos));
        }
    }
}
