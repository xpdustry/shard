package com.xpdustry.shard.compat.v160.blocks.sandbox;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.sync.*;
import com.xpdustry.shard.sim.blocks.sandbox.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.wire.v160_7.*;

/**
 * The sandbox and campaign blocks' own Calls ({@link SandboxEvent}), to everyone: {@code unitTetherBlockSpawned(tile,
 * id)} for a target dummy's new unit, {@code landingPadLanded(tile)} for a landing pad's pod.
 */
@Register
public final class SandboxCallMapping implements CallMapping<SandboxEvent>{
    @Override
    public Class<SandboxEvent> event(){
        return SandboxEvent.class;
    }

    @Override
    public void map(SandboxEvent event, CallContext context, CallSink sink){
        TileGrid tiles = context.world().tiles();
        int pos = Point2.pack(tiles.x(event.pos()), tiles.y(event.pos()));
        switch(event){
            case SandboxEvent.TetherSpawned spawned -> sink.all(new Packets.UnitTetherBlockSpawned(pos, spawned.unit()));
            case SandboxEvent.PadLanded ignored -> sink.all(new Packets.LandingPadLanded(pos));
        }
    }
}
