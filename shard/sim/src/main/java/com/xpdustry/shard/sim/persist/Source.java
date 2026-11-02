package com.xpdustry.shard.sim.persist;

import java.lang.invoke.*;
import java.nio.*;

/**
 * Reads what a {@link Sink} wrote. Every read is bounds-checked and every length is checked against the bytes left
 * before anything is allocated, so a corrupt section fails with a {@link CheckpointException}, never with a huge
 * allocation.
 */
final class Source{
    private static final VarHandle SHORT = MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle INT = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle LONG = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    private final String name;
    private final byte[] bytes;
    private final int end;
    private int position;

    Source(String name, byte[] bytes, int offset, int length){
        this.name = name;
        this.bytes = bytes;
        this.position = offset;
        this.end = offset + length;
    }

    Source(String name, byte[] bytes){
        this(name, bytes, 0, bytes.length);
    }

    int b(){
        need(1);
        return bytes[position++];
    }

    boolean bool(){
        int value = b();
        if(value != 0 && value != 1) throw corrupt("a boolean is " + value);
        return value == 1;
    }

    short s(){
        need(2);
        short value = (short)SHORT.get(bytes, position);
        position += 2;
        return value;
    }

    int i(){
        need(4);
        int value = (int)INT.get(bytes, position);
        position += 4;
        return value;
    }

    long l(){
        need(8);
        long value = (long)LONG.get(bytes, position);
        position += 8;
        return value;
    }

    float f(){
        return Float.intBitsToFloat(i());
    }

    double d(){
        return Double.longBitsToDouble(l());
    }

    String str(){
        int length = i();
        if(length == -1) return null;
        length(length, 2);
        var chars = new char[length];
        for(int c = 0; c < length; c++){
            chars[c] = (char)(short)SHORT.get(bytes, position);
            position += 2;
        }
        return new String(chars);
    }

    /** A length read from the stream, checked against what is left at {@code width} bytes per element. */
    int length(int length, int width){
        if(length < 0 || (long)length * width > end - position) throw corrupt("a length of " + length + " overruns the section");
        return length;
    }

    void bytes(byte[] into, int offset, int length){
        need(length);
        System.arraycopy(bytes, position, into, offset, length);
        position += length;
    }

    void shorts(short[] into, int offset, int length){
        need(length * 2);
        ByteBuffer.wrap(bytes, position, length * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(into, offset, length);
        position += length * 2;
    }

    void ints(int[] into, int offset, int length){
        need(length * 4);
        ByteBuffer.wrap(bytes, position, length * 4).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(into, offset, length);
        position += length * 4;
    }

    void longs(long[] into, int offset, int length){
        need(length * 8);
        ByteBuffer.wrap(bytes, position, length * 8).order(ByteOrder.LITTLE_ENDIAN).asLongBuffer().get(into, offset, length);
        position += length * 8;
    }

    void floats(float[] into, int offset, int length){
        need(length * 4);
        for(int i = 0; i < length; i++){
            into[offset + i] = Float.intBitsToFloat((int)INT.get(bytes, position));
            position += 4;
        }
    }

    void doubles(double[] into, int offset, int length){
        need(length * 8);
        for(int i = 0; i < length; i++){
            into[offset + i] = Double.longBitsToDouble((long)LONG.get(bytes, position));
            position += 8;
        }
    }

    void skip(int count){
        need(count);
        position += count;
    }

    /** Bytes read so far. */
    int position(){
        return position;
    }

    int remaining(){
        return end - position;
    }

    /** Fails unless every byte was read: a section that reads short is as corrupt as one that reads long. */
    void finish(){
        if(position != end) throw corrupt((end - position) + " bytes left unread");
    }

    CheckpointException corrupt(String why){
        return new CheckpointException("section " + name + " is corrupt: " + why);
    }

    private void need(int count){
        if(count < 0 || count > end - position) throw corrupt("it ends early");
    }
}
