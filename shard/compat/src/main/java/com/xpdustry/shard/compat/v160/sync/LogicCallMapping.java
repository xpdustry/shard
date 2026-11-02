package com.xpdustry.shard.compat.v160.sync;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.logic.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.wire.v160_7.Packets;

/**
 * The Calls processors make ({@link LogicEvent}), to everyone, in the order they were published, tiles as
 * {@code Point2.pack} ({@code TypeIO.writeTile}):
 * <ul>
 *   <li>{@code SyncVariable}: {@code syncVariable(build, variable, value)} (LExecutor.java:2077), unreliable, the
 *   value as {@link WireValues} writes it;</li>
 *   <li>{@code SetFlag}: {@code setFlag(flag, add)};</li>
 *   <li>{@code SetTile}, {@code SetFloor}, {@code SetOverlay}: {@code setTile}, {@code setFloor} and
 *   {@code setOverlay}, from {@code setblock};</li>
 *   <li>{@code SetMapArea}: {@code setMapArea(x, y, w, h)}.</li>
 * </ul>
 */
@Register
public final class LogicCallMapping implements CallMapping<LogicEvent>{
    @Override
    public Class<LogicEvent> event(){
        return LogicEvent.class;
    }

    @Override
    public void map(LogicEvent event, CallContext context, CallSink sink){
        TileGrid tiles = context.world().tiles();
        switch(event){
            case LogicEvent.SyncVariable e -> sink.all(new Packets.SyncVariable(pack(tiles, e.pos()), e.variable(), WireValues.of(e.value(), context.world())));
            case LogicEvent.SetFlag e -> sink.all(new Packets.SetFlag(e.flag(), e.add()));
            case LogicEvent.SetTile e -> sink.all(new Packets.SetTile(pack(tiles, e.pos()), (short)e.block(), e.team(), e.rotation()));
            case LogicEvent.SetFloor e -> sink.all(new Packets.SetFloor(pack(tiles, e.pos()), (short)e.floor(), (short)e.overlay()));
            case LogicEvent.SetOverlay e -> sink.all(new Packets.SetOverlay(pack(tiles, e.pos()), (short)e.overlay()));
            case LogicEvent.SetMapArea e -> sink.all(new Packets.SetMapArea(e.x(), e.y(), e.width(), e.height()));
        }
    }

    private static int pack(TileGrid tiles, int pos){
        return Point2.pack(tiles.x(pos), tiles.y(pos));
    }
}
