package com.xpdustry.shard.net;

import java.util.*;

/**
 * A game packet a client sent, as the transport hands it to its {@link Transport.Handler}.
 *
 * @param id the packet id (0–255)
 * @param payload the decompressed payload, owned by the receiver
 * @param reliable whether it came over TCP rather than UDP
 * @param arrivalNanos when the transport read it, on its clock ({@link System#nanoTime()} outside tests). Intents carry
 *     this recorded value; the sim never reads a clock (ARCHITECTURE.md §4).
 */
public record Packet(int id, byte[] payload, boolean reliable, long arrivalNanos){
    public Packet{
        Objects.requireNonNull(payload, "payload");
    }
}
