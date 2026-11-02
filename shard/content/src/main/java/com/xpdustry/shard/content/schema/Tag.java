package com.xpdustry.shard.content.schema;

/**
 * Who reads a field (ARCHITECTURE.md §5). Vanilla clients simulate buildings, bullets and units themselves, so a
 * world whose content differs from vanilla must send every {@code shared} and {@code client} difference as a patch.
 */
public enum Tag{
    /** Read only by code that runs on the server (unit AI): changes apply at the next tick and need no patch. */
    SERVER,
    /** Read by the simulation on both sides: a change needs a patch, or the client's prediction drifts. */
    SHARED,
    /** Looks and UI only: a change needs a patch to be seen, never to stay in sync. */
    CLIENT;

    public static Tag of(String name){
        return valueOf(name.toUpperCase(java.util.Locale.ROOT));
    }
}
