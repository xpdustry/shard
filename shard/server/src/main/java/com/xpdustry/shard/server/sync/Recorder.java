package com.xpdustry.shard.server.sync;

import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * A full-vision observer at the {@code SYNC} phase: the replay side quest's live recorder (note 37 §7, request 7;
 * the view stream of {@code docs/replay/FORMAT.md}). It sees the world after every {@link #frameEvery()}-th tick, after
 * {@code GAME_STATE}, as a view frame must, with every event published since its last frame, in order. Frames fall on
 * absolute ticks that are multiples of {@link #frameEvery()}, so with a divisor of 600 every keyframe tick is a frame
 * and matches a match checkpoint ({@code LifecyclePolicy.matchCheckpoints}).
 *
 * <p>Calls run on the world's tick thread. Read the world, copy what the frame needs and return: encoding and
 * deflating belong on another thread, as checkpoint compression does. Never change the world, never block, never keep
 * references to its objects past the call.
 */
public interface Recorder{
    /** Ticks between frames; at least 1. */
    int frameEvery();

    /** The world it records, at its first frame boundary after being added, or after the world was restored. */
    default void attached(World world){
    }

    /**
     * One frame: {@code world} after the tick {@code world.ticks()}, and the events since the previous frame (since
     * {@link #attached} for the first).
     */
    void frame(World world, List<WorldEvent> events);

    /** Removed, or the world stopped or failed. No call follows. */
    default void detached(){
    }
}
