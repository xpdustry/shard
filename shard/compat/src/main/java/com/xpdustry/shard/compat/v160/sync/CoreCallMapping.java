package com.xpdustry.shard.compat.v160.sync;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.blocks.storage.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.wire.v160_7.Packets;

/**
 * A core's Calls ({@link CoreEvent}), to everyone, tiles as {@code Point2.pack}:
 * <ul>
 *   <li>{@code PlayerSpawn}: {@code playerSpawn(tile, player)} (CoreBlock.java:646). The client moves the player to
 *   the core; the unit reaches it with the next entity snapshot.</li>
 *   <li>{@code SetTile}: {@code setTile(tile, block, team, rotation)}, a captured core's {@code setNet}.</li>
 * </ul>
 */
@Register
public final class CoreCallMapping implements CallMapping<CoreEvent>{
    @Override
    public Class<CoreEvent> event(){
        return CoreEvent.class;
    }

    @Override
    public void map(CoreEvent event, CallContext context, CallSink sink){
        TileGrid tiles = context.world().tiles();
        int tile = Point2.pack(tiles.x(event.pos()), tiles.y(event.pos()));
        switch(event){
            case CoreEvent.PlayerSpawn e -> sink.all(new Packets.PlayerSpawn(tile, e.player()));
            case CoreEvent.SetTile e -> sink.all(new Packets.SetTile(tile, (short)e.block(), e.team(), e.rotation()));
        }
    }
}
