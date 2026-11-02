package com.xpdustry.shard.server.world;

import com.xpdustry.shard.sim.world.*;

import java.nio.file.*;
import java.util.*;

/**
 * Follows the matches the {@link WorldManager}'s worlds play, for replay bundles (note 37
 * §5): when a world becomes resident in a match, when a match checkpoint is durable, and when a match is over. A match
 * is one game of one map in one world: it starts when a fresh world opens and lasts through hibernation, wakes,
 * recoveries and, with {@link LifecyclePolicy#resume}, server restarts. Its id is stored in every checkpoint's
 * {@code match} label.
 *
 * <p>Calls must not block: they come from the server thread and from {@code io}.
 */
public interface MatchListener{
    MatchListener NONE = new MatchListener(){
    };

    /**
     * {@code id} is resident in {@code match} at {@code tick}: opened fresh ({@code fresh}), or resumed, woken or
     * recovered from a checkpoint at {@code tick}. On the server thread, before its first tick: {@code world} may be
     * read here (a recorder samples it), and observed from here on.
     *
     * @param labels what the host said about the world when it made it (its map and mode)
     */
    default void resident(WorldId id, String match, long tick, boolean fresh, SortedMap<String, String> labels, World world){
    }

    /**
     * {@code id} is about to write a match checkpoint at {@code tick}, in a match it has been playing: its interval
     * says so (it follows the world's live config, so it may have just turned on). On the world's tick thread, after
     * the tick and before the checkpoint's fork; {@code world} may be read here, and nothing slow may run here. Not called by worlds with no match checkpoints, nor when the world becomes resident
     * ({@link #resident}).
     */
    default void keyframe(WorldId id, String match, long tick, SortedMap<String, String> labels, World world){
    }

    /**
     * {@code world} hibernated at {@code tick}: it is on disk only, and nothing ticks it until it wakes, maybe in
     * another process (a server stopped with {@link LifecyclePolicy#resume}). On the server thread.
     */
    default void hibernated(WorldId world, String match, long tick){
    }

    /** The match checkpoint at {@code tick} is durable at {@code file}. On {@code io}. */
    default void checkpoint(WorldId world, String match, long tick, Path file){
    }

    /** {@code match} is over in {@code world}: the world stopped, or a new world under its id replaced it. */
    default void ended(WorldId world, String match){
    }
}
