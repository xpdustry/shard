package com.xpdustry.shard.sim.world;

import java.util.*;

/**
 * One decoded client packet, as the transport hands it over (ARCHITECTURE.md §4, step 1).
 *
 * @param session the authenticated session
 * @param routeGeneration the session's route to this world; a stale generation is rejected after a transfer
 * @param sequence the session's packet sequence; admission rejects anything not above the last admitted one
 * @param arrivalNanos when the transport received the packet, on the server's monotonic clock. The sim never reads
 *     a clock; movement validation reads this recorded value instead.
 * @param payload the decoded intent
 */
public record IntentEnvelope(long session, int routeGeneration, long sequence, long arrivalNanos, Intent payload)
    implements Input{
    public IntentEnvelope{
        Objects.requireNonNull(payload, "payload");
    }
}
