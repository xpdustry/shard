package com.xpdustry.shard.server.sync;

import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.Packet;

/**
 * A server-side event that already knows its Call and its recipients: a reply to the acting session (a position
 * correction, a config rollback) or a client RPC's forward echo (WP26's {@code IntentCall}s). The world publishes it
 * in the order the Call goes out, and the Outbox sends it as it comes, held for loading players like any other Call.
 * Events of {@code :sim} use a {@code CallMapping} instead, as {@code :sim} knows nothing of audiences.
 */
public interface Outbound extends WorldEvent{
    Audience audience();

    Packet packet();
}
