package com.xpdustry.shard.server.session;

/**
 * Where a {@link Session} is in its route (ARCHITECTURE.md §7). The cycle is
 * {@code IN_WORLD -> TRANSFERRING -> LOADING -> IN_WORLD}; a new session enters it through {@code CONNECTED -> LOADING},
 * and any state may go to {@code CLOSED}.
 */
public enum SessionState{
    /** Transport registered, no world yet: waiting for the connect packet. */
    CONNECTED,
    /** A member of its destination, which streams the world; waiting for the matching {@code connectConfirm}. */
    LOADING,
    /** Playing: its packets become intents for its world. */
    IN_WORLD,
    /** Removed from the source world, not yet a member of the destination. */
    TRANSFERRING,
    CLOSED;

    /** Whether the session is a member of {@link Session#world()}. */
    public boolean member(){
        return this == LOADING || this == IN_WORLD;
    }
}
