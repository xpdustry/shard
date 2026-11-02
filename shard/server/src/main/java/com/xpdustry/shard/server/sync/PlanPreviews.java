package com.xpdustry.shard.server.sync;

import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.Codecs;

import java.util.*;

/**
 * One batch of a player's plan previews ({@code clientPlanSnapshot(player, groupId, plans)}), which the world's sync
 * assembles into the player's current previews as {@code PlayerComp.handlePreviewPlans} does, and shows the player's
 * teammates every half second. W2.14's {@code PlayerIntents.Previewed} is one.
 */
public interface PlanPreviews extends WorldEvent{
    long session();

    /** The batch's group: a newer group starts over, an older one is dropped. */
    int groupId();

    List<Codecs.ClientPlan> plans();
}
