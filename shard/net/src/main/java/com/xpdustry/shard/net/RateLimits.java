package com.xpdustry.shard.net;

import java.util.*;

/**
 * Token-bucket limits on what one connection may send (ARCHITECTURE.md §8 "Rate limits"): one bucket over every
 * message, as vanilla's packet spam limit ({@code ArcNetProvider.java:166}: 300 per 3 s, then a kick), plus optional
 * buckets per packet id. The transport knows no game packets; the server sets the per-id limits.
 *
 * @param all the limit on every message, framework messages included, as vanilla counts them
 * @param perId the limit of each packet id that has one, indexed by id (0–255)
 */
public record RateLimits(Limit all, List<Limit> perId){
    /**
     * Vanilla's budget, as a bucket: a burst of 300 refilled at 100 per second. Over it, the connection closes and its
     * address is blacklisted ({@code ArcNetProvider.java:166-169}).
     */
    public static final Limit VANILLA_SPAM = new Limit(300, 100, Action.BLACKLIST);

    public RateLimits{
        Objects.requireNonNull(all, "all");
        if(perId.size() != 256) throw new IllegalArgumentException("perId needs 256 entries");
        perId = Collections.unmodifiableList(new ArrayList<>(perId)); // List.copyOf refuses the nulls
    }

    /** Only vanilla's spam limit. */
    public static RateLimits vanilla(){
        return new RateLimits(VANILLA_SPAM, Collections.nCopies(256, null));
    }

    /** These limits plus {@code limit} for packet {@code id}. */
    public RateLimits with(int id, Limit limit){
        var ids = new ArrayList<>(perId);
        ids.set(id, limit);
        return new RateLimits(all, ids);
    }

    /** The limit of packet {@code id}, or null. */
    public Limit of(int id){
        return perId.get(id);
    }

    /** What happens to a message over its limit. */
    public enum Action{
        /** Drop it and count it; the connection stays open. */
        DROP,
        /** Close the connection. */
        CLOSE,
        /**
         * Close the connection and tell the handler ({@link Transport.Handler#blacklisted}): vanilla adds the address to
         * its DOS list for packet and chat spam ({@code ArcNetProvider.java:169}, {@code NetClient.java:341}).
         */
        BLACKLIST;

        /** Whether a message over the limit closes the connection. */
        public boolean closes(){
            return this != DROP;
        }
    }

    /** A bucket of {@code burst} tokens refilled at {@code perSecond}. */
    public record Limit(int burst, double perSecond, Action action){
        public Limit{
            Objects.requireNonNull(action, "action");
            if(burst <= 0 || !(perSecond > 0)) throw new IllegalArgumentException("burst and rate must be positive");
        }

        TokenBucket bucket(long nowNanos){
            return new TokenBucket(burst, perSecond, nowNanos);
        }
    }
}
