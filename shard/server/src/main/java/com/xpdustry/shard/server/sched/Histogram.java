package com.xpdustry.shard.server.sched;

import java.util.concurrent.atomic.*;

/**
 * A log-linear histogram of microseconds: 8 sub-buckets per power of two, from 1 µs to about 70 minutes, so a
 * quantile is within 12.5 %. Recording is lock-free and safe from any thread. Ported from note 24's {@code Hist}.
 */
public final class Histogram{
    private static final int SUB = 8, BUCKETS = 32 * SUB;
    private final AtomicLongArray counts = new AtomicLongArray(BUCKETS);
    private final AtomicLong max = new AtomicLong();

    public void record(long micros){
        if(micros < 0) micros = 0;
        counts.incrementAndGet(index(micros));
        long seen = max.get();
        while(micros > seen && !max.compareAndSet(seen, micros)) seen = max.get();
    }

    /** Records nanoseconds, rounded down to microseconds. */
    public void recordNanos(long nanos){
        record(nanos / 1000);
    }

    private static int index(long value){
        if(value < SUB) return (int)value;
        int exponent = 63 - Long.numberOfLeadingZeros(value);
        int mantissa = (int)(value >>> (exponent - 3)) & (SUB - 1);
        return Math.min(BUCKETS - 1, (exponent - 2) * SUB + mantissa);
    }

    private static long upper(int index){
        if(index < SUB) return index;
        int exponent = index / SUB + 2, mantissa = index % SUB;
        return ((long)(SUB + mantissa + 1) << (exponent - 3)) - 1;
    }

    public long count(){
        long total = 0;
        for(int i = 0; i < BUCKETS; i++) total += counts.get(i);
        return total;
    }

    /** The upper bound of quantile {@code q} in µs, at bucket resolution; 0 when empty. */
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

    /** A copy of the counts so far, then a reset: for windowed reports. */
    public Histogram drain(){
        var out = new Histogram();
        for(int i = 0; i < BUCKETS; i++) out.counts.set(i, counts.getAndSet(i, 0));
        out.max.set(max.getAndSet(0));
        return out;
    }

    public void merge(Histogram other){
        for(int i = 0; i < BUCKETS; i++) counts.addAndGet(i, other.counts.get(i));
        long theirs = other.max.get();
        long seen = max.get();
        while(theirs > seen && !max.compareAndSet(seen, theirs)) seen = max.get();
    }

    public String summary(){
        return String.format("n=%d p50=%dus p99=%dus p999=%dus max=%dus", count(), quantile(0.5), quantile(0.99), quantile(0.999), max());
    }

    @Override
    public String toString(){
        return summary();
    }
}
