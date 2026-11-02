package com.xpdustry.shard.sim.world;

/**
 * The decoded payload of a client packet: a build plan queue, a unit command, a config tap. {@code :server} decodes
 * wire bytes into one of these records, so {@code :sim} never sees bytes. Implementations are immutable records,
 * owned by the subsystem they drive.
 */
public interface Intent{
    /**
     * Applies this intent at admission, after the world checked membership, route and sequence. Check permissions,
     * syntax and limits here and return the verdict; mutate only on {@link Verdict#ADMITTED}. Plans that will be
     * revalidated at the builder's turn are queued, not applied (ARCHITECTURE.md §4 "Intents → commands").
     */
    Verdict apply(World world, IntentEnvelope envelope);
}
