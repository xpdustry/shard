package com.xpdustry.shard.server.sync;

import com.xpdustry.shard.sim.world.*;

/**
 * A player asked for one of its team's buildings ({@code requestBlockSnapshot(player, pos)}, already checked): the
 * world's sync answers with that building's {@code blockSnapshot}, to the player only (NetServer.java:728). W2.14's
 * {@code BlockIntents.SnapshotWanted} is one.
 */
public interface SnapshotRequest extends WorldEvent{
    long session();

    /** The building, as {@code Point2.pack} of its origin. */
    int pos();
}
