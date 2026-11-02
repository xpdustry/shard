package com.xpdustry.shard.net;

/**
 * A token bucket: holds at most {@code burst} tokens and refills {@code perSecond} of them per second. Time comes in as
 * an argument (monotonic nanoseconds), so tests drive it without sleeping. Not thread-safe: a {@link Conn} guards its
 * buckets.
 */
public final class TokenBucket{
    private final double burst, perNano;
    private double tokens;
    private long last;

    public TokenBucket(int burst, double perSecond, long nowNanos){
        if(burst <= 0 || !(perSecond > 0)) throw new IllegalArgumentException("burst and rate must be positive");
        this.burst = burst;
        this.perNano = perSecond / 1e9;
        this.tokens = burst;
        this.last = nowNanos;
    }

    /** Takes one token if there is one. */
    public boolean tryTake(long nowNanos){
        long elapsed = nowNanos - last;
        if(elapsed > 0){
            tokens = Math.min(burst, tokens + elapsed * perNano);
            last = nowNanos;
        }
        if(tokens < 1) return false;
        tokens -= 1;
        return true;
    }

    /** Tokens available now, without taking one. */
    public double available(long nowNanos){
        long elapsed = Math.max(0, nowNanos - last);
        return Math.min(burst, tokens + elapsed * perNano);
    }
}
