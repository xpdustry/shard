package com.xpdustry.shard.server.intent;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.compat.v160.entity.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.wire.*;

/**
 * Config values between the wire ({@link WireObjects}) and the sim, through {@link WirePlans} on the world's own
 * content ids, which are the client's. On top of it: buildings and units, which a config names by position and id.
 */
final class WireValues{
    private WireValues(){
    }

    /**
     * A config the client sent, as the sim reads it; buildings and units are resolved by the caller. Content the world
     * doesn't have reads as null, as {@code content.getByID} gives it to vanilla.
     */
    static Object toSim(Object wire, ContentEpoch content){
        return WirePlans.value(wire, WirePlans.ContentIds.of(content));
    }

    /** A sim value as {@code TypeIO.writeObject} writes it: a building by its position, a unit by its id. */
    static Object toWire(Object value, ContentEpoch content){
        return switch(value){
            case Building build -> new WireObjects.BuildingRef(build.pos());
            case Unit unit -> new WireObjects.UnitIdRef(unit.id());
            // Point2[] and Vec2[] keep their own wire types
            case Object[] values when !(values instanceof Point2[]) && !(values instanceof Vec2[]) -> {
                var out = new Object[values.length];
                for(int i = 0; i < values.length; i++) out[i] = toWire(values[i], content);
                yield out;
            }
            case null, default -> WirePlans.config(value, WirePlans.ContentIds.of(content));
        };
    }

    /** A plan as the client sent it, as the sim holds it. */
    static BuildPlan plan(Codecs.Plan plan, ContentEpoch content){
        return WirePlans.plan(plan, WirePlans.ContentIds.of(content));
    }
}
