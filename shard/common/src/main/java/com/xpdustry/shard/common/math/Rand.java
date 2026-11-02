package com.xpdustry.shard.common.math;

/**
 * Arc's {@code arc.math.Rand}: xorshift128+, with the same seeding (two MurmurHash3 finalizer passes) and the same
 * derived draws, bit for bit. Unlike Arc's class it does not extend {@link java.util.Random}, has no unseeded
 * constructor and is not thread-safe. A world owns exactly one; its two longs are part of every checkpoint.
 */
public final class Rand{
    /** Normalization constant for double. */
    private static final double NORM_DOUBLE = 1.0 / (1L << 53);
    /** Normalization constant for float. */
    private static final double NORM_FLOAT = 1.0 / (1L << 24);

    /** The first half of the internal state. */
    public long seed0;
    /** The second half of the internal state. */
    public long seed1;

    public Rand(long seed){
        setSeed(seed);
    }

    public Rand(long seed0, long seed1){
        setState(seed0, seed1);
    }

    /** A copy with the same state, which then draws the same sequence. */
    public Rand copy(){
        return new Rand(seed0, seed1);
    }

    private static long murmurHash3(long x){
        x ^= x >>> 33;
        x *= 0xff51afd7ed558ccdL;
        x ^= x >>> 33;
        x *= 0xc4ceb9fe1a85ec53L;
        x ^= x >>> 33;

        return x;
    }

    public long nextLong(){
        long s1 = this.seed0;
        final long s0 = this.seed1;
        this.seed0 = s0;
        s1 ^= s1 << 23;
        return (this.seed1 = (s1 ^ s0 ^ (s1 >>> 17) ^ (s0 >>> 26))) + s0;
    }

    /** The low {@code bits} bits of the next long; {@code java.util.Random.next} as Arc overrides it. */
    public int next(int bits){
        return (int)(nextLong() & ((1L << bits) - 1));
    }

    public int nextInt(){
        return (int)nextLong();
    }

    /** Uniform in [0, n). Throws for n &lt;= 0. */
    public int nextInt(final int n){
        return (int)nextLong(n);
    }

    /** Uniform in [0, n), by rejection. Throws for n &lt;= 0. */
    public long nextLong(final long n){
        if(n <= 0) throw new IllegalArgumentException("n must be positive");
        for(;;){
            final long bits = nextLong() >>> 1;
            final long value = bits % n;
            if(bits - value + (n - 1) >= 0) return value;
        }
    }

    public double nextDouble(){
        return (nextLong() >>> 11) * NORM_DOUBLE;
    }

    public float nextFloat(){
        return (float)((nextLong() >>> 40) * NORM_FLOAT);
    }

    public boolean nextBoolean(){
        return (nextLong() & 1) != 0;
    }

    public void nextBytes(final byte[] bytes){
        int n;
        int i = bytes.length;
        while(i != 0){
            n = i < 8 ? i : 8; // min(i, 8);
            for(long bits = nextLong(); n-- != 0; bits >>= 8)
                bytes[--i] = (byte)bits;
        }
    }

    /** Seeds from one long; 0 is replaced by {@link Long#MIN_VALUE}, as in Arc. */
    public void setSeed(final long seed){
        long seed0 = murmurHash3(seed == 0 ? Long.MIN_VALUE : seed);
        setState(seed0, murmurHash3(seed0));
    }

    public boolean chance(double chance){
        return nextDouble() < chance;
    }

    public float range(float amount){
        return nextFloat() * amount * 2 - amount;
    }

    public float random(float max){
        return nextFloat() * max;
    }

    /** Inclusive. */
    public int random(int max){
        return nextInt(max + 1);
    }

    public float random(float min, float max){
        return min + (max - min) * nextFloat();
    }

    public int range(int amount){
        return nextInt(amount * 2 + 1) - amount;
    }

    public int random(int min, int max){
        if(min >= max) return min;
        return min + nextInt(max - min + 1);
    }

    public void setState(final long seed0, final long seed1){
        this.seed0 = seed0;
        this.seed1 = seed1;
    }

    /** @param seed 0 or 1, which of the two state longs to return */
    public long getState(int seed){
        return seed == 0 ? seed0 : seed1;
    }

    @Override
    public String toString(){
        return "Rand[" + Long.toHexString(seed0) + ", " + Long.toHexString(seed1) + "]";
    }
}
