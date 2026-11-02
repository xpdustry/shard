package com.xpdustry.shard.server;

import java.time.Duration;

/**
 * The one wait a test makes for something that must happen: a failure detector, reached only when the thing hangs.
 * It is long on purpose. The full check runs many test JVMs at once, and a world's reload that takes 1 s alone has
 * taken 12 s there, so a deadline of seconds measures the machine and not the code. {@value #MINUTES} minutes is 15
 * times that slowdown; a passing test never waits it out.
 *
 * <p>Use it for "this future completes", "this latch opens", "this packet arrives". For "this takes a while", count
 * ticks, frames or messages instead of reading a clock.
 */
public final class Hang{
    private static final int MINUTES = 3;
    public static final Duration TIMEOUT = Duration.ofMinutes(MINUTES);
    public static final long SECONDS = TIMEOUT.toSeconds();
    public static final long MILLIS = TIMEOUT.toMillis();
    public static final long NANOS = TIMEOUT.toNanos();

    private Hang(){
    }
}
