package com.xpdustry.shard.sim.objectives;

import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.world.*;

/**
 * The objective and marker Calls, as domain events, published after their handler ran on the server, as vanilla's
 * {@code called = Loc.server} Calls do. Markers are copies taken when the event was published.
 *
 * <p>World processors' marker statements publish none: clients run every world processor themselves, and vanilla's
 * {@code MakeMarkerI} and {@code SetMarkerI} send nothing (LExecutor.java:2282-2352). The marker events come from the
 * server's own API ({@link Objectives}).
 */
public sealed interface ObjectiveEvent extends WorldEvent{
    long tick();

    /** {@code Call.completeObjective(index)}. */
    record Completed(long tick, int index) implements ObjectiveEvent{
    }

    /** {@code Call.clearObjectives()}. */
    record Cleared(long tick) implements ObjectiveEvent{
    }

    /** {@code Call.setObjectives(executor)}, with a copy of the rules' objectives. */
    record Set(long tick, MapObjectives objectives) implements ObjectiveEvent{
    }

    /** {@code Call.createMarker(id, marker)}. Unreliable. */
    record MarkerCreated(long tick, int id, ObjectiveMarker marker) implements ObjectiveEvent{
    }

    /** {@code Call.removeMarker(id)}. Unreliable. */
    record MarkerRemoved(long tick, int id) implements ObjectiveEvent{
    }

    /** {@code Call.updateMarker(id, control, p1, p2, p3)}. Unreliable. */
    record MarkerUpdated(long tick, int id, LMarkerControl control, double p1, double p2, double p3) implements ObjectiveEvent{
    }

    /** {@code Call.updateMarkerText(id, type, fetch, text)}. Unreliable. */
    record MarkerText(long tick, int id, LMarkerControl type, boolean fetch, String text) implements ObjectiveEvent{
    }

    /** {@code Call.updateMarkerTexture(id, texture)}: the texture as {@code TypeIO.writeObject} carries it. Unreliable. */
    record MarkerTexture(long tick, int id, ObjectiveMarker.Texture texture) implements ObjectiveEvent{
    }
}
