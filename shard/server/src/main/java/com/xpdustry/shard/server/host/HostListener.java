package com.xpdustry.shard.server.host;

import com.xpdustry.shard.sim.world.*;

/**
 * Server code that reacts to a hosted world's ticks: chat commands, the game flow, plugins. It runs on the world's
 * tick thread after each tick, once the tick's Calls went out and its intents' kicks were delivered, so what it sends
 * follows them, as vanilla's event listeners follow what {@code Logic.update} sent. It must not block, and it must
 * not touch another world's state: offer to that world's {@code Outbox} instead. A listener that throws is logged and
 * the world goes on.
 *
 * <p>It hears the world's intent events ({@code IntentEvent}: chat lines, menu answers, plugin packets), game
 * events ({@code GameEvent}: game over) and core changes ({@code CoreChangeEvent}: vanilla's event of that name), in
 * publication order. Register it with {@link Host#listen}.
 */
public interface HostListener{
    /** One of the tick's intent, game or core change events. */
    default void event(HostedWorld world, World state, WorldEvent event){
    }

    /** After every tick, once the tick's events were handed to every listener. */
    default void ticked(HostedWorld world, World state){
    }

    /**
     * {@code world} left the host and no longer ticks: {@linkplain Host#stop stopped}, or {@linkplain Host#replace
     * replaced} by its next map. From any thread. What the listener keeps for it must go, or it would hold the world,
     * and nothing it scheduled for it may run: a world hosted under the same id is a new one.
     */
    default void retired(HostedWorld world){
    }
}
