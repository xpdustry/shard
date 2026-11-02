package com.xpdustry.shard.server.world;

import com.xpdustry.shard.sim.world.*;

/**
 * What runs around a {@link ManagedWorld}'s ticks: the server's side of a hosted world (its session mailboxes, the
 * world sends and snapshots). The world calls it on its tick thread, one call at a time, except where noted.
 */
public interface WorldHost{
    /** No host: the world only ticks. */
    WorldHost NONE = new WorldHost(){
    };

    /**
     * The world is resident: opened, woken or recovered. A woken world is a new {@code World}, with a new inbox and
     * observers. On the server thread, before its first turn.
     */
    default void attached(World world){
    }

    /** Right before each tick: pump inputs into {@code world.inbox()}. */
    default void beforeTick(World world){
    }

    /** Right after each tick, before the boundary: react to the tick's events, send what is due. */
    default void afterTick(World world){
    }

    /** A maintenance turn instead of a tick (the world is empty and its time frozen). */
    default void maintain(World world){
    }

    /**
     * Whether something for the world waits outside its inbox, or a route into it is starting: the world must not idle
     * or hibernate. Any thread; it must not block.
     */
    default boolean busy(){
        return false;
    }

    /** The world failed and left memory. On the tick thread that ran the failing turn. */
    default void failed(Throwable failure){
    }
}
