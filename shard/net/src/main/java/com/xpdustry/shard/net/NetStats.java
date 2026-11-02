package com.xpdustry.shard.net;

import java.util.concurrent.atomic.*;

/** Counters of one {@link Transport}, safe to read from any thread. */
public final class NetStats{
    /** Frames read over TCP and datagrams received over UDP, framework messages included. */
    public final LongAdder tcpIn = new LongAdder(), udpIn = new LongAdder();
    /** Frames queued for TCP, datagrams sent, and the gathering writes that carried the TCP frames. */
    public final LongAdder tcpOut = new LongAdder(), udpOut = new LongAdder(), tcpWrites = new LongAdder();
    public final LongAdder tcpBytesOut = new LongAdder(), udpBytesOut = new LongAdder();
    /** Connections closed for a full write queue or a stalled write. */
    public final LongAdder slowClients = new LongAdder();
    /** Connections closed by the handshake or frame deadline. */
    public final LongAdder deadlines = new LongAdder();
    /** Messages dropped by a {@link RateLimits.Action#DROP} limit, and connections closed by a limit (blacklisting included). */
    public final LongAdder rateDropped = new LongAdder(), rateClosed = new LongAdder();
    /** Datagrams dropped: malformed, oversized, from unknown addresses, or failed sends. */
    public final LongAdder udpDropped = new LongAdder();
    /** Datagrams never queued, because the connection's unreliable bytes were at their cap. */
    public final LongAdder udpShed = new LongAdder();
    /** {@code RegisterUDP} datagrams whose source IP differed from the TCP peer's. */
    public final LongAdder udpHijacks = new LongAdder();
    /** Discovery replies sent, and requests refused by the discovery limit. */
    public final LongAdder discoveries = new LongAdder(), discoveriesRefused = new LongAdder();
}
