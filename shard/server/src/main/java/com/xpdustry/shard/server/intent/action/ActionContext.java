package com.xpdustry.shard.server.intent.action;

import com.xpdustry.shard.server.intent.*;
import com.xpdustry.shard.sim.world.*;

/**
 * What an {@link ActionFilter} may read and do besides the action: the world as it is at admission, the settings the
 * packet was decoded under, when it arrived, and the acting player's input state. What it tells the player becomes
 * Calls after the tick commits; it never touches a connection.
 */
public interface ActionContext{
    /** The world the action is admitted in. Read it; mutate nothing. */
    World world();

    Policy policy();

    /** When the transport received the packet, in milliseconds on the server's monotonic clock: a recorded input. */
    long arrivalMillis();

    /** The acting player's {@link PlayerInput}, whose rate keepers built-in filters count with. */
    PlayerInput input();

    /** A chat line for the acting player ({@code player.sendMessage}). */
    void message(String text);

    /**
     * Kicks the acting player with {@code reason} and keeps them out for {@code banMillis}
     * ({@code player.kick(reason, duration)}). Nothing else from them is admitted.
     */
    void kick(String reason, long banMillis);
}
