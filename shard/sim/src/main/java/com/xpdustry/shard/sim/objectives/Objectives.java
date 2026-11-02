package com.xpdustry.shard.sim.objectives;

import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.world.*;

/**
 * The objective and marker Calls a server makes on its own, for the server's code and plugins: each runs vanilla's
 * handler on this world (NetClient.java:466-482, LExecutor.java:2355-2389), then publishes its {@link ObjectiveEvent},
 * which {@code :compat} sends to everyone. Call them on the world's thread, between or during ticks.
 */
public final class Objectives{
    private Objectives(){
    }

    /** {@code Call.completeObjective(index)}. */
    public static void complete(World world, int index){
        Rules rules = world.part(WorldRules.class).rules;
        rules.objectives.complete(world, rules, index);
    }

    /** {@code Call.clearObjectives()}. */
    public static void clear(World world){
        world.part(WorldRules.class).rules.objectives.clear();
        world.publish(new ObjectiveEvent.Cleared(world.ticks()));
    }

    /** {@code Call.setObjectives(executor)}: the rules take {@code objectives}, and clients a copy. */
    public static void set(World world, MapObjectives objectives){
        world.part(WorldRules.class).rules.objectives = objectives;
        world.publish(new ObjectiveEvent.Set(world.ticks(), objectives.copy()));
    }

    /** {@code Call.createMarker(id, marker)}: the world keeps {@code marker}, and clients a copy. */
    public static void createMarker(World world, int id, ObjectiveMarker marker){
        ObjectiveState.of(world).markers.add(id, marker);
        world.publish(new ObjectiveEvent.MarkerCreated(world.ticks(), id, marker.copy()));
    }

    /** {@code Call.removeMarker(id)}. */
    public static void removeMarker(World world, int id){
        ObjectiveState.of(world).markers.remove(id);
        world.publish(new ObjectiveEvent.MarkerRemoved(world.ticks(), id));
    }

    /** {@code Call.updateMarker(id, control, p1, p2, p3)}: NaN is "no value", as in {@code setmarker}. */
    public static void updateMarker(World world, int id, LMarkerControl control, double p1, double p2, double p3){
        MapMarkers markers = ObjectiveState.of(world).markers;
        ObjectiveMarker marker = markers.get(id);
        if(marker != null) marker.control(markers, control, p1, p2, p3);
        world.publish(new ObjectiveEvent.MarkerUpdated(world.ticks(), id, control, p1, p2, p3));
    }

    /** {@code Call.updateMarkerText(id, type, fetch, text)}: only {@code flushText} sets the text. */
    public static void updateMarkerText(World world, int id, LMarkerControl type, boolean fetch, String text){
        ObjectiveMarker marker = ObjectiveState.of(world).markers.get(id);
        if(marker != null && type == LMarkerControl.flushText) marker.setText(text, fetch);
        world.publish(new ObjectiveEvent.MarkerText(world.ticks(), id, type, fetch, text));
    }

    /** {@code Call.updateMarkerTexture(id, texture)}. */
    public static void updateMarkerTexture(World world, int id, ObjectiveMarker.Texture texture){
        ObjectiveMarker marker = ObjectiveState.of(world).markers.get(id);
        if(marker != null) marker.setTexture(texture);
        world.publish(new ObjectiveEvent.MarkerTexture(world.ticks(), id, texture));
    }
}
