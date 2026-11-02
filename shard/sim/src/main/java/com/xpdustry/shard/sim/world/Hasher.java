package com.xpdustry.shard.sim.world;

/**
 * A deterministic, order-sensitive 64-bit hash over primitive state, for {@link WorldHash}. Floats hash by their bits,
 * so {@code -0f} differs from {@code 0f}; exactness is the point. Every NaN hashes as the canonical NaN: IEEE 754 lets
 * each engine pick NaN payloads (HotSpot and the Wasm engines do differ), and no Java comparison can see them. Not
 * cryptographic.
 */
public final class Hasher{
    private static final long P1 = 0x9E3779B97F4A7C15L, P2 = 0xC2B2AE3D27D4EB4FL, P3 = 0x165667B19E3779F9L;
    private long state = 0x27D4EB2F165667C5L;
    private long count;

    public Hasher l(long value){
        state = Long.rotateLeft(state ^ (value * P2), 31) * P1 + P3;
        count++;
        return this;
    }

    public Hasher i(int value){
        return l(value);
    }

    public Hasher bool(boolean value){
        return l(value ? 1 : 0);
    }

    public Hasher f(float value){
        return l(Float.floatToIntBits(value));
    }

    public Hasher d(double value){
        return l(Double.doubleToLongBits(value));
    }

    public Hasher str(String value){
        l(value.length());
        for(int i = 0; i < value.length(); i++) l(value.charAt(i));
        return this;
    }

    public Hasher ints(int[] values, int offset, int length){
        l(length);
        for(int i = offset; i < offset + length; i++) l(values[i]);
        return this;
    }

    public Hasher ints(int[] values){
        return ints(values, 0, values.length);
    }

    public Hasher shorts(short[] values){
        l(values.length);
        for(short value : values) l(value);
        return this;
    }

    public Hasher bytes(byte[] values){
        l(values.length);
        for(byte value : values) l(value);
        return this;
    }

    public Hasher floats(float[] values, int offset, int length){
        l(length);
        for(int i = offset; i < offset + length; i++) f(values[i]);
        return this;
    }

    public Hasher floats(float[] values){
        return floats(values, 0, values.length);
    }

    /** The hash of everything fed so far (splitmix64's finalizer over the state and the count). */
    public long value(){
        long z = state ^ count * P3;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
