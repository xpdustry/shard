package com.xpdustry.shard.compat.v160.sync;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.v160_7.*;

/**
 * A building's Calls ({@link BuildingEvent}), with its origin tile as {@code Point2.pack}, vanilla's
 * {@code build.pos()}:
 * <ul>
 *   <li>{@code Destroyed}: {@code buildDestroyed(build)}, to everyone. The client's handler runs {@code killed()},
 *   which clears the tiles, so their {@code TileEvent}s send nothing.</li>
 *   <li>{@code Configured}: {@code tileConfig(player, build, value)} ({@code InputHandler.java:718}). A client's own
 *   config ({@link BuildingEvent.Configured#forwarded}) has its Call already: its intent sends vanilla's forward to
 *   everyone else ({@code BlockIntents.TileConfig}), so it maps to nothing here. Every other config is the server's
 *   {@code Call.tileConfig(null, build, value)} ({@code configureAny}, logic, plugins), to everyone with no player,
 *   whatever its cause. The value is the frozen {@link ConfigValue}, written as {@link WireValues} says.</li>
 *   <li>{@code HealthChanged}: no Call of its own; it queues the building for the next {@code buildHealthUpdate}
 *   batch.</li>
 * </ul>
 */
@Register
public final class BuildingCallMapping implements CallMapping<BuildingEvent>{
    @Override
    public Class<BuildingEvent> event(){
        return BuildingEvent.class;
    }

    @Override
    public void map(BuildingEvent event, CallContext context, CallSink sink){
        TileGrid tiles = context.world().tiles();
        int pos = Point2.pack(tiles.x(event.pos()), tiles.y(event.pos()));
        switch(event){
            case BuildingEvent.Destroyed ignored -> sink.all(new Packets.BuildDestroyed(pos));
            case BuildingEvent.Configured configured -> {
                if(!configured.forwarded()) sink.all(new Packets.TileConfig(-1, pos, WireValues.of(configured.value(), context.world())));
            }
            case BuildingEvent.HealthChanged changed -> context.healthChanged(changed.pos());
        }
    }
}
