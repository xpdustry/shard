package com.xpdustry.shard.server.chat;

import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * What a {@link ChatFilter} may read and do besides the line: the world at admission, the speaking player and its
 * {@link ChatInput}, the settings the line arrived under and when it arrived. What it tells the player becomes Calls
 * after the tick commits; it never touches a connection.
 */
public interface ChatContext{
    /** Read it; mutate nothing but the player's {@link #input()}. */
    World world();

    Player player();

    ChatInput input();

    ChatPolicy policy();

    /** When the transport received the line, in milliseconds on the server's monotonic clock: a recorded input. */
    long arrivalMillis();

    /** {@code player.sendMessage(text)}. */
    void message(String text);

    /** {@code player.con.kick(reason, duration)}: nothing more from the player is admitted. */
    void kick(String reason, long banMillis);
}
