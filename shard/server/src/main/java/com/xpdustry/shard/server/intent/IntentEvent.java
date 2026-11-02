package com.xpdustry.shard.server.intent;

import com.xpdustry.shard.sim.world.*;

/**
 * What an admitted intent tells the server, after the tick commits: the Calls it sends ({@link IntentCall}), and what
 * the server or a plugin acts on that the world doesn't (a tap, an admin request, a menu choice, a custom packet).
 * The host hands every one to {@link Intents#deliver}.
 */
public interface IntentEvent extends WorldEvent{
}
