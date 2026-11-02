package com.xpdustry.shard.compat.v160.sync;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.blocks.defense.*;
import com.xpdustry.shard.sim.build.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.*;
import com.xpdustry.shard.wire.v160_7.*;

/**
 * Construction's Calls ({@link BuildEvent}), each the packet of the same name, to everyone: vanilla's
 * {@code @Remote(called = Loc.server)} methods in {@code Build} and {@code ConstructBlock}. Units are written as
 * {@code TypeIO.writeUnit}, a standard unit (kind 2) and its id, a build tower's block unit (kind 1) by its tile, or
 * null (kind 0) for none; tiles as
 * {@code Point2.pack}; configs as {@link WireValues} says.
 */
@Register
public final class BuildCallMapping implements CallMapping<BuildEvent>{
    @Override
    public Class<BuildEvent> event(){
        return BuildEvent.class;
    }

    @Override
    public void map(BuildEvent event, CallContext context, CallSink sink){
        sink.all(switch(event){
            case BuildEvent.BeginPlace e -> new Packets.BeginPlace(unit(e.unit(), context.world()), (short)e.block(), e.team(), e.x(), e.y(), e.rotation(),
                WireValues.of(e.config(), context.world()));
            case BuildEvent.BeginBreak e -> new Packets.BeginBreak(unit(e.unit(), context.world()), e.team(), e.x(), e.y());
            case BuildEvent.ConstructFinish e -> new Packets.ConstructFinish(Point2.pack(e.x(), e.y()), (short)e.block(), unit(e.builder(), context.world()),
                (byte)e.rotation(), e.team(), WireValues.of(e.config(), context.world()));
            case BuildEvent.DeconstructFinish e -> new Packets.DeconstructFinish(Point2.pack(e.x(), e.y()), (short)e.block(), unit(e.builder(), context.world()));
        });
    }

    private static Codecs.UnitRef unit(int id, World world){
        if(id < 0) return Codecs.UnitRef.NONE;
        BuildTurret tower = BuildTurret.ownerOf(world, id);
        return tower != null ? Codecs.UnitRef.block(tower.pos()) : Codecs.UnitRef.unit(id);
    }
}
