package com.xpdustry.shard.compat.v160.sync;

import com.xpdustry.shard.wire.*;

/**
 * Where a {@link CallMapping} sends its packets, and to whom: the {@code Outbox}'s visibility classes
 * (ARCHITECTURE.md §8). The server decides which sessions each one reaches, encodes each packet once for all of them,
 * and holds them for players still loading the world. Calls keep their order across every class.
 */
public interface CallSink{
    /** Every player in the world: vanilla's {@code Call.x(...)} on a server. */
    void all(Packet packet);

    /**
     * Every player but {@code session}'s: a client's Call the server ran and forwards to the others
     * ({@code forward = true}), as the sender already ran it.
     */
    void except(long session, Packet packet);

    /** The players on {@code team}. */
    void team(int team, Packet packet);

    /**
     * The players who can see the point, in world units: effects, sounds and bullets. Without fog that is every
     * player, as in vanilla.
     */
    void at(float x, float y, Packet packet);

    /** Only {@code session}'s player: vanilla's {@code Call.x(con, ...)}. */
    void only(long session, Packet packet);
}
