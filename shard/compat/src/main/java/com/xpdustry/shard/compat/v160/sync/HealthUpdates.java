package com.xpdustry.shard.compat.v160.sync;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.v160_7.*;

import java.util.*;

/**
 * Vanilla's building health sync ({@code NetServer.java:1078} and {@code :1352-1378}): a building's
 * {@code healthChanged()} queues its position, and every half second the queue goes out as
 * {@code buildHealthUpdate} packets of {@code (pos, floatToRawIntBits(health))} pairs, read when they are sent. A
 * packet closes once it holds 200 ints (800 bytes); a position whose building is gone is skipped.
 *
 * <p>The queue is Arc's {@code IntSet}, kept for the world's life and cleared after each send as vanilla's is, so the
 * pairs come out in vanilla's order: the table's layout depends on everything ever added.
 */
public final class HealthUpdates{
    /** {@code buildHealthChanged}: {@code Point2}-packed origins. */
    private final IntSet changed = new IntSet();

    /** {@code buildHealthUpdate(build)}: the building whose origin is the tile {@code pos} ({@code y * width + x}). */
    public void changed(World world, int pos){
        changed.add(Point2.pack(world.tiles().x(pos), world.tiles().y(pos)));
    }

    /** Whether anything waits: vanilla polls its half-second timer only then. */
    public boolean pending(){
        return changed.size > 0;
    }

    /** The queue as packets, with the health the buildings have now; the queue is empty afterwards. */
    public List<Packets.BuildHealthUpdate> drain(World world){
        var packets = new ArrayList<Packets.BuildHealthUpdate>();
        var seq = new IntSeq(Batch.MAX / 4 + 1);
        changed.each(pos -> {
            Building building = world.buildings().at(Point2.x(pos), Point2.y(pos));
            if(building != null) seq.add(pos, Float.floatToRawIntBits(building.health));
            if(seq.size * 4 >= Batch.MAX){
                packets.add(new Packets.BuildHealthUpdate(seq.toArray()));
                seq.size = 0;
            }
        });
        if(seq.size > 0) packets.add(new Packets.BuildHealthUpdate(seq.toArray()));
        changed.clear();
        return packets;
    }
}
