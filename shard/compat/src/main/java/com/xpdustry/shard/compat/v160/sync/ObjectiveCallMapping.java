package com.xpdustry.shard.compat.v160.sync;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.rules.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.objectives.*;
import com.xpdustry.shard.wire.*;
import com.xpdustry.shard.wire.v160_7.LMarkerControl;
import com.xpdustry.shard.wire.v160_7.Packets;

import java.util.*;

/**
 * The objective and marker Calls ({@link ObjectiveEvent}), to everyone, in the order they were published:
 * {@code completeObjective(index)}, {@code clearObjectives()} and {@code setObjectives(executor)} (NetClient.java:466),
 * and the unreliable {@code createMarker}, {@code removeMarker}, {@code updateMarker}, {@code updateMarkerText} and
 * {@code updateMarkerTexture} (LExecutor.java:2355). Objectives and markers go as {@link ObjectivesJson} writes them.
 */
@Register
public final class ObjectiveCallMapping implements CallMapping<ObjectiveEvent>{
    private static final LMarkerControl[] CONTROLS = LMarkerControl.values();

    @Override
    public Class<ObjectiveEvent> event(){
        return ObjectiveEvent.class;
    }

    @Override
    public void map(ObjectiveEvent event, CallContext context, CallSink sink){
        ContentEpoch content = context.world().content();
        switch(event){
            case ObjectiveEvent.Completed e -> sink.all(new Packets.CompleteObjective(e.index()));
            case ObjectiveEvent.Cleared e -> sink.all(new Packets.ClearObjectives());
            case ObjectiveEvent.Set e -> sink.all(new Packets.SetObjectives(new ObjectivesJson(content).writeObjectives(e.objectives())));
            case ObjectiveEvent.MarkerCreated e -> sink.all(new Packets.CreateMarker(e.id(), new ObjectivesJson(content).writeMarker(e.marker())));
            case ObjectiveEvent.MarkerRemoved e -> sink.all(new Packets.RemoveMarker(e.id()));
            case ObjectiveEvent.MarkerUpdated e -> sink.all(new Packets.UpdateMarker(e.id(), control(e.control()), e.p1(), e.p2(), e.p3()));
            case ObjectiveEvent.MarkerText e -> sink.all(new Packets.UpdateMarkerText(e.id(), control(e.type()), e.fetch(), e.text()));
            case ObjectiveEvent.MarkerTexture e -> sink.all(new Packets.UpdateMarkerTexture(e.id(), texture(e.texture(), context)));
        }
    }

    /** The wire's copy of the enum: both are vanilla's, in vanilla's order. */
    static LMarkerControl control(com.xpdustry.shard.logic.lang.LMarkerControl control){
        return CONTROLS[control.ordinal()];
    }

    /**
     * {@code TypeIO.writeObject(texture)}: a name as a string, content as content, a building as its position, else
     * null. Planets and sector presets, which Shard has no ids for, go as null.
     */
    static Object texture(ObjectiveMarker.Texture texture, CallContext context){
        return switch(texture.kind()){
            case STRING -> texture.name();
            case BUILDING -> new WireObjects.BuildingRef(texture.pos());
            case CONTENT -> {
                ContentEpoch content = context.world().content();
                for(ContentType type : List.of(ContentType.WEATHER, ContentType.BLOCK, ContentType.UNIT, ContentType.LIQUID, ContentType.STATUS,
                    ContentType.ITEM)){
                    int id = content.id(type, texture.name());
                    if(id >= 0) yield WireValues.of(new ContentRef(type, id), context.world());
                }
                yield null;
            }
            case NONE -> null;
        };
    }
}
