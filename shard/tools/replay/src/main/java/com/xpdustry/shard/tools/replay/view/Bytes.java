package com.xpdustry.shard.tools.replay.view;

import java.nio.charset.*;
import java.util.*;

/**
 * A growable little-endian byte buffer with the view stream's integer encodings: {@code varint} is unsigned LEB128,
 * {@code svarint} is zigzag LEB128. {@link In} reads it back.
 */
final class Bytes{
    private byte[] data;
    private int size;

    Bytes(int capacity){
        data = new byte[Math.max(16, capacity)];
    }

    int size(){
        return size;
    }

    byte[] toArray(){
        return Arrays.copyOf(data, size);
    }

    void clear(){
        size = 0;
    }

    private void ensure(int more){
        if(size + more > data.length) data = Arrays.copyOf(data, Math.max(data.length * 2, size + more));
    }

    Bytes u8(int value){
        ensure(1);
        data[size++] = (byte)value;
        return this;
    }

    Bytes u16(int value){
        ensure(2);
        data[size++] = (byte)value;
        data[size++] = (byte)(value >>> 8);
        return this;
    }

    Bytes u32(int value){
        ensure(4);
        for(int i = 0; i < 4; i++) data[size++] = (byte)(value >>> (8 * i));
        return this;
    }

    Bytes varint(long value){
        if(value < 0) throw new IllegalArgumentException("negative varint " + value);
        ensure(10);
        while((value & ~0x7FL) != 0){
            data[size++] = (byte)((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        data[size++] = (byte)value;
        return this;
    }

    Bytes svarint(long value){
        return varint((value << 1) ^ (value >> 63));
    }

    Bytes bytes(byte[] bytes){
        ensure(bytes.length);
        System.arraycopy(bytes, 0, data, size, bytes.length);
        size += bytes.length;
        return this;
    }

    Bytes bytes(Bytes other){
        ensure(other.size);
        System.arraycopy(other.data, 0, data, size, other.size);
        size += other.size;
        return this;
    }

    Bytes ascii(String text){
        return bytes(text.getBytes(StandardCharsets.US_ASCII));
    }

    /** Reads what {@link Bytes} writes, failing with the offset on a short or malformed buffer. */
    static final class In{
        private final byte[] data;
        private int at;
        private final int end;

        In(byte[] data){
            this(data, 0, data.length);
        }

        In(byte[] data, int offset, int length){
            this.data = data;
            this.at = offset;
            this.end = offset + length;
        }

        int position(){
            return at;
        }

        boolean more(){
            return at < end;
        }

        private void need(int count){
            if(count < 0 || count > end - at) throw new IllegalArgumentException("the buffer ends at byte " + end + ", " + count + " more needed at " + at);
        }

        /** Bytes left. */
        int remaining(){
            return end - at;
        }

        int u8(){
            need(1);
            return data[at++] & 0xFF;
        }

        int u16(){
            need(2);
            int value = (data[at] & 0xFF) | (data[at + 1] & 0xFF) << 8;
            at += 2;
            return value;
        }

        int u32(){
            need(4);
            int value = 0;
            for(int i = 0; i < 4; i++) value |= (data[at + i] & 0xFF) << (8 * i);
            at += 4;
            return value;
        }

        long varint(){
            long value = 0;
            for(int shift = 0; shift < 64; shift += 7){
                int b = u8();
                // the tenth byte holds bit 63 only
                if(shift == 63 && (b & 0x7E) != 0) throw new IllegalArgumentException("a varint over 64 bits at " + (at - 1));
                value |= (long)(b & 0x7F) << shift;
                if((b & 0x80) == 0) return value;
            }
            throw new IllegalArgumentException("a varint longer than 10 bytes at " + at);
        }

        /** A u32 length of bytes that follow it: at most what is left. */
        int length(){
            long length = u32() & 0xFFFFFFFFL;
            if(length > end - at) throw new IllegalArgumentException("a length of " + length + " at " + (at - 4) + ", with " + (end - at) + " bytes left");
            return (int)length;
        }

        /** A varint count of items that take at least a byte each: at most what is left. */
        int count(){
            long count = varint();
            if(count > end - at) throw new IllegalArgumentException("a count of " + count + " at " + at + ", with " + (end - at) + " bytes left");
            return (int)count;
        }

        int varintInt(){
            return Math.toIntExact(varint());
        }

        long svarint(){
            long raw = varint();
            return (raw >>> 1) ^ -(raw & 1);
        }

        int svarintInt(){
            return Math.toIntExact(svarint());
        }

        byte[] bytes(int count){
            need(count);
            byte[] out = Arrays.copyOfRange(data, at, at + count);
            at += count;
            return out;
        }
    }
}
