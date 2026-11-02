package com.xpdustry.shard.common.io;

import java.io.*;
import java.util.*;

/**
 * Arc's {@code arc.util.io.Writes} over a growable byte array: big-endian, the same bytes as Arc's over a
 * {@link DataOutputStream}. {@link #f(float)} and {@link #d(double)} collapse NaN to the canonical bits, as
 * {@code DataOutput.writeFloat} does. {@link #str(String)} is {@code writeUTF}: a u16 length, then modified UTF-8
 * (NUL as two bytes, astral characters as two surrogates of three bytes each), at most 65,535 encoded bytes.
 */
public final class Writes{
    private byte[] bytes;
    private int size;

    public Writes(){
        this(64);
    }

    public Writes(int capacity){
        bytes = new byte[Math.max(capacity, 8)];
    }

    /** write long */
    public void l(long i){
        ensure(8);
        for(int shift = 56; shift >= 0; shift -= 8) bytes[size++] = (byte)(i >>> shift);
    }

    /** write int */
    public void i(int i){
        ensure(4);
        bytes[size++] = (byte)(i >>> 24);
        bytes[size++] = (byte)(i >>> 16);
        bytes[size++] = (byte)(i >>> 8);
        bytes[size++] = (byte)i;
    }

    /** write byte: the low 8 bits of {@code i} */
    public void b(int i){
        ensure(1);
        bytes[size++] = (byte)i;
    }

    /** write bytes */
    public void b(byte[] array, int offset, int length){
        Objects.checkFromIndexSize(offset, length, array.length);
        ensure(length);
        System.arraycopy(array, offset, bytes, size, length);
        size += length;
    }

    /** write bytes */
    public void b(byte[] array){
        b(array, 0, array.length);
    }

    /** write boolean (one byte, 1 or 0) */
    public void bool(boolean b){
        b(b ? 1 : 0);
    }

    /** write short: the low 16 bits of {@code i} */
    public void s(int i){
        ensure(2);
        bytes[size++] = (byte)(i >>> 8);
        bytes[size++] = (byte)i;
    }

    /** write float, NaN canonicalized */
    public void f(float f){
        i(Float.floatToIntBits(f));
    }

    /** write double, NaN canonicalized */
    public void d(double d){
        l(Double.doubleToLongBits(d));
    }

    /**
     * write string ({@code writeUTF}). Throws {@link UncheckedIOException} wrapping {@link UTFDataFormatException},
     * before writing anything, when the encoding exceeds 65,535 bytes.
     */
    public void str(String str){
        int length = str.length();
        long encoded = 0;
        for(int i = 0; i < length; i++){
            char c = str.charAt(i);
            encoded += c >= 0x0001 && c <= 0x007F ? 1 : c > 0x07FF ? 3 : 2;
        }
        if(encoded > 65535){
            throw new UncheckedIOException(new UTFDataFormatException("encoded string too long: " + encoded + " bytes"));
        }
        ensure(2 + (int)encoded);
        bytes[size++] = (byte)(encoded >>> 8);
        bytes[size++] = (byte)encoded;
        for(int i = 0; i < length; i++){
            char c = str.charAt(i);
            if(c >= 0x0001 && c <= 0x007F){
                bytes[size++] = (byte)c;
            }else if(c > 0x07FF){
                bytes[size++] = (byte)(0xE0 | ((c >> 12) & 0x0F));
                bytes[size++] = (byte)(0x80 | ((c >> 6) & 0x3F));
                bytes[size++] = (byte)(0x80 | (c & 0x3F));
            }else{
                bytes[size++] = (byte)(0xC0 | ((c >> 6) & 0x1F));
                bytes[size++] = (byte)(0x80 | (c & 0x3F));
            }
        }
    }

    /** Bytes written so far. */
    public int size(){
        return size;
    }

    /** A copy of the bytes written. */
    public byte[] toByteArray(){
        return Arrays.copyOf(bytes, size);
    }

    /** The backing array, valid up to {@link #size()}; replaced when the buffer grows. */
    public byte[] buffer(){
        return bytes;
    }

    /** Forgets what was written, keeping the buffer. */
    public void reset(){
        size = 0;
    }

    private void ensure(int more){
        if(size + more > bytes.length){
            bytes = Arrays.copyOf(bytes, Math.max(size + more, bytes.length * 2));
        }
    }
}
