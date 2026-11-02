package com.xpdustry.shard.tools.bots;

import java.util.concurrent.atomic.*;

/** Note 24's log-linear histogram of microseconds: 8 sub-buckets per power of two, 1 µs to about 70 min. Thread-safe. */
final class Hist{
    private static final int SUB = 8, BUCKETS = 32 * SUB;
    private final AtomicLongArray counts = new AtomicLongArray(BUCKETS);
    private final AtomicLong max = new AtomicLong();

    public void record(long micros){
        if(micros < 0) micros = 0;
        counts.incrementAndGet(index(micros));
        max.accumulateAndGet(micros, Math::max);
    }

    private static int index(long v){
        if(v < SUB) return (int)v;
        int exp = 63 - Long.numberOfLeadingZeros(v); // >= 3
        int mantissa = (int)(v >>> (exp - 3)) & (SUB - 1);
        return Math.min(BUCKETS - 1, (exp - 2) * SUB + mantissa);
    }

    private static long upper(int index){
        if(index < SUB) return index;
        int exp = index / SUB + 2, mantissa = index % SUB;
        return ((long)(SUB + mantissa + 1) << (exp - 3)) - 1;
    }

    public long count(){
        long n = 0;
        for(int i = 0; i < BUCKETS; i++) n += counts.get(i);
        return n;
    }

    /** Quantile upper bound in µs (bucket resolution: within 12.5%). */
    public long quantile(double q){
        long total = count();
        if(total == 0) return 0;
        long target = (long)Math.ceil(q * total), seen = 0;
        for(int i = 0; i < BUCKETS; i++){
            seen += counts.get(i);
            if(seen >= target) return Math.min(upper(i), max.get());
        }
        return max.get();
    }

    public long max(){
        return max.get();
    }

    /** Copies and resets, for windowed reports. */
    public Hist drain(){
        Hist out = new Hist();
        for(int i = 0; i < BUCKETS; i++) out.counts.set(i, counts.getAndSet(i, 0));
        out.max.set(max.getAndSet(0));
        return out;
    }

    public void merge(Hist other){
        for(int i = 0; i < BUCKETS; i++) counts.addAndGet(i, other.counts.get(i));
        max.accumulateAndGet(other.max.get(), Math::max);
    }

    public String summary(){
        return String.format("n=%d p50=%dus p99=%dus p999=%dus max=%dus", count(), quantile(0.5), quantile(0.99), quantile(0.999), max());
    }
}
