package com.xpdustry.shard.compat.v160.sync;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.compat.v160.entity.*;
import com.xpdustry.shard.logic.lang.LAccess;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.*;

/**
 * An event's value, as {@link ConfigValue#of} froze it, as the object {@code TypeIO.writeObject} writes for what it
 * stands for: a building as its {@code Point2}-packed position (tag 12), a unit as its id (tag 17), a team as its id
 * (tag 20), a sensor as its {@code LAccess} ordinal (tag 13), an int array as an {@code IntSeq} (tag 6), the way
 * {@link WirePlans#config} sends a config; arrays element by element, a {@code Vec2[]} as one (tag 18). Plain values,
 * tech nodes and tag-21 {@link ConfigValue.IntArray}s go through {@link WirePlans#config}.
 */
public final class WireValues{
    private WireValues(){
    }

    /** {@code value} as {@link WireObjects#writeObject} takes it, in {@code world}'s positions and content ids. */
    public static Object of(Object value, World world){
        return of(value, world.tiles(), WirePlans.ContentIds.of(world.content()));
    }

    private static Object of(Object value, TileGrid tiles, WirePlans.ContentIds ids){
        return switch(value){
            case ConfigValue.Bytes bytes -> bytes.bytes();
            case ConfigValue.Booleans booleans -> booleans.booleans();
            case ConfigValue.Ints ints -> new WireObjects.IntSeqValue(ints.ints());
            case ConfigValue.Points points -> points.points().toArray(Point2[]::new);
            case ConfigValue.Values values -> {
                var out = new Object[values.values().size()];
                for(int i = 0; i < out.length; i++) out[i] = of(values.values().get(i), tiles, ids);
                yield out;
            }
            case ConfigValue.Vec vec -> new Codecs.Vec2(vec.x(), vec.y());
            case ConfigValue.Vecs vecs -> {
                var out = new Codecs.Vec2[vecs.vecs().size()];
                for(int i = 0; i < out.length; i++){
                    ConfigValue.Vec vec = vecs.vecs().get(i);
                    out[i] = vec == null ? null : new Codecs.Vec2(vec.x(), vec.y());
                }
                yield out;
            }
            case ConfigValue.BuildingAt at -> new WireObjects.BuildingRef(Point2.pack(tiles.x(at.pos()), tiles.y(at.pos())));
            case ConfigValue.EntityId entity -> new WireObjects.UnitIdRef(entity.id());
            case ConfigValue.Team team -> new WireObjects.TeamRef(team.id());
            case LAccess sensor -> new WireObjects.LAccessRef(sensor.ordinal());
            case null, default -> WirePlans.config(value, ids);
        };
    }
}
