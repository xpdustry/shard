package com.xpdustry.shard.compat.v160.sync;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.wire.*;
import com.xpdustry.shard.wire.v160_7.Packets;

/**
 * Items moving between units and buildings ({@link ItemEvent}), to everyone, in the order they were published; tiles
 * as {@code Point2.pack}, units as {@code TypeIO.writeUnit} writes them (none, a turret's block unit by its position,
 * or a standard unit by id):
 * <ul>
 *   <li>{@code TransferItemTo}: {@code transferItemTo(unit, item, amount, x, y, build)};</li>
 *   <li>{@code TakeItems}: {@code takeItems(build, item, amount, to)}.</li>
 * </ul>
 * A building that isn't there is written as vanilla writes a null one, -1.
 */
@Register
public final class ItemCallMapping implements CallMapping<ItemEvent>{
    @Override
    public Class<ItemEvent> event(){
        return ItemEvent.class;
    }

    @Override
    public void map(ItemEvent event, CallContext context, CallSink sink){
        TileGrid tiles = context.world().tiles();
        switch(event){
            case ItemEvent.TransferItemTo e -> sink.all(new Packets.TransferItemTo(unit(e.unit(), e.blockUnit()), (short)e.item(), e.amount(), e.x(), e.y(),
                pack(tiles, e.pos())));
            case ItemEvent.TakeItems e -> sink.all(new Packets.TakeItems(pack(tiles, e.pos()), (short)e.item(), e.amount(), unit(e.unit(), e.blockUnit())));
        }
    }

    private static Codecs.UnitRef unit(int id, int blockUnit){
        if(blockUnit != -1) return Codecs.UnitRef.block(blockUnit);
        return id == -1 ? Codecs.UnitRef.NONE : Codecs.UnitRef.unit(id);
    }

    private static int pack(TileGrid tiles, int pos){
        return pos == -1 ? -1 : Point2.pack(tiles.x(pos), tiles.y(pos));
    }
}
