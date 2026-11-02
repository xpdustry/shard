package com.xpdustry.shard.net;

import java.time.Duration;

/**
 * The one wait a test makes for something that must happen: a failure detector, reached only when the thing hangs.
 * It is long on purpose. The full check runs many test JVMs at once, so a deadline of seconds measures the machine
 * and not the code. {@value #MINUTES} minutes is far past anything a loaded VM has taken; a passing test never waits it
 * out. (The server's tests have their own copy: test source sets don't share classes.)
 */
final class Hang{
    private static final int MINUTES = 3;
    static final Duration TIMEOUT = Duration.ofMinutes(MINUTES);
    static final long SECONDS = TIMEOUT.toSeconds();
    static final long MILLIS = TIMEOUT.toMillis();
    static final long NANOS = TIMEOUT.toNanos();

    private Hang(){
    }
}
