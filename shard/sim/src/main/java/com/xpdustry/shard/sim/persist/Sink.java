package com.xpdustry.shard.sim.persist;

import java.lang.invoke.*;
import java.nio.*;
import java.util.*;

/**
 * A growable little-endian byte buffer: the raw bytes of one checkpoint section. Floats and doubles keep their raw
 * bits, NaN payloads included, because restoring must be exact. Primitive arrays go in with one bulk copy each.
 */
final class Sink{
    private static final VarHandle SHORT = MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle INT = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle LONG = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    private byte[] bytes;
    private int size;

    Sink(int capacity){
        bytes = new byte[Math.max(capacity, 16)];
    }

    void b(int value){
        ensure(1);
        bytes[size++] = (byte)value;
    }

    void s(int value){
        ensure(2);
        SHORT.set(bytes, size, (short)value);
        size += 2;
    }

    void i(int value){
        ensure(4);
        INT.set(bytes, size, value);
        size += 4;
    }

    void l(long value){
        ensure(8);
        LONG.set(bytes, size, value);
        size += 8;
    }

    void f(float value){
        i(Float.floatToRawIntBits(value));
    }

    void d(double value){
        l(Double.doubleToRawLongBits(value));
    }

    /** A string as its UTF-16 code units, so unpaired surrogates survive; null is length -1. */
    void str(String value){
        if(value == null){
            i(-1);
            return;
        }
        int length = value.length();
        i(length);
        ensure(length * 2);
        for(int c = 0; c < length; c++){
            SHORT.set(bytes, size, (short)value.charAt(c));
            size += 2;
        }
    }

    void bytes(byte[] values, int offset, int length){
        ensure(length);
        System.arraycopy(values, offset, bytes, size, length);
        size += length;
    }

    void shorts(short[] values, int offset, int length){
        ensure(length * 2);
        ByteBuffer.wrap(bytes, size, length * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(values, offset, length);
        size += length * 2;
    }

    void ints(int[] values, int offset, int length){
        ensure(length * 4);
        ByteBuffer.wrap(bytes, size, length * 4).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(values, offset, length);
        size += length * 4;
    }

    void longs(long[] values, int offset, int length){
        ensure(length * 8);
        ByteBuffer.wrap(bytes, size, length * 8).order(ByteOrder.LITTLE_ENDIAN).asLongBuffer().put(values, offset, length);
        size += length * 8;
    }

    /** Raw bits, element by element, so no NaN is ever canonicalized. */
    void floats(float[] values, int offset, int length){
        ensure(length * 4);
        for(int i = 0; i < length; i++){
            INT.set(bytes, size, Float.floatToRawIntBits(values[offset + i]));
            size += 4;
        }
    }

    void doubles(double[] values, int offset, int length){
        ensure(length * 8);
        for(int i = 0; i < length; i++){
            LONG.set(bytes, size, Double.doubleToRawLongBits(values[offset + i]));
            size += 8;
        }
    }

    /** Overwrites the int at {@code position}, for lengths written after their contents. */
    void i(int position, int value){
        Objects.checkFromIndexSize(position, 4, size);
        INT.set(bytes, position, value);
    }

    /** Drops everything from byte {@code size} on. */
    void truncate(int size){
        if(size < 0 || size > this.size) throw new IllegalArgumentException("truncate to " + size + " of " + this.size);
        this.size = size;
    }

    int size(){
        return size;
    }

    /** The backing array, valid up to {@link #size()}. */
    byte[] buffer(){
        return bytes;
    }

    byte[] toByteArray(){
        return Arrays.copyOf(bytes, size);
    }

    private void ensure(int more){
        if(more < 0) throw new IllegalArgumentException("negative length");
        if(size + more > bytes.length){
            long grown = Math.max((long)size + more, (long)bytes.length * 2);
            if(grown > Integer.MAX_VALUE - 16) throw new IllegalStateException("a checkpoint section can't exceed 2 GiB");
            bytes = Arrays.copyOf(bytes, (int)grown);
        }
    }
}
