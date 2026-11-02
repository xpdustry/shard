package com.xpdustry.shard.common.io;

import java.io.*;
import java.util.*;

/**
 * Arc's {@code arc.util.io.Reads} over a byte array slice: big-endian, the same values and failures as Arc's over a
 * {@link DataInputStream}. Reading past the end throws {@link UncheckedIOException} wrapping {@link EOFException};
 * malformed modified UTF-8 throws it wrapping {@link UTFDataFormatException}; a string over its limit throws
 * {@link StringTooLongException}. {@link #skip(int)} stops quietly at the end, as {@code skipBytes} does.
 */
public final class Reads{
    private final byte[] bytes;
    private final int limit;
    private int position;

    public Reads(byte[] bytes){
        this(bytes, 0, bytes.length);
    }

    public Reads(byte[] bytes, int offset, int length){
        Objects.checkFromIndexSize(offset, length, bytes.length);
        this.bytes = bytes;
        this.position = offset;
        this.limit = offset + length;
    }

    /** -1 at the end, else the next byte (consumed), as Arc's over an {@link InputStream}. */
    public int checkEOF(){
        return position < limit ? bytes[position++] & 0xFF : -1;
    }

    /** read long */
    public long l(){
        need(8);
        long value = 0;
        for(int i = 0; i < 8; i++) value = value << 8 | (bytes[position++] & 0xFF);
        return value;
    }

    /** read int */
    public int i(){
        need(4);
        return (bytes[position++] & 0xFF) << 24 | (bytes[position++] & 0xFF) << 16
            | (bytes[position++] & 0xFF) << 8 | bytes[position++] & 0xFF;
    }

    /** read short */
    public short s(){
        need(2);
        return (short)((bytes[position++] & 0xFF) << 8 | bytes[position++] & 0xFF);
    }

    /** read unsigned short */
    public int us(){
        return s() & 0xFFFF;
    }

    /** read byte */
    public byte b(){
        need(1);
        return bytes[position++];
    }

    /** allocate and read a byte array */
    public byte[] b(int length){
        if(length < 0) throw new NegativeArraySizeException(String.valueOf(length));
        need(length);
        byte[] out = Arrays.copyOfRange(bytes, position, position + length);
        position += length;
        return out;
    }

    /** read into the whole array */
    public byte[] b(byte[] array){
        return b(array, 0, array.length);
    }

    /** read into part of the array */
    public byte[] b(byte[] array, int offset, int length){
        Objects.checkFromIndexSize(offset, length, array.length);
        need(length);
        System.arraycopy(bytes, position, array, offset, length);
        position += length;
        return array;
    }

    /** read unsigned byte */
    public int ub(){
        return b() & 0xFF;
    }

    /** read boolean: any non-zero byte is true */
    public boolean bool(){
        return b() != 0;
    }

    /** read float */
    public float f(){
        return Float.intBitsToFloat(i());
    }

    /** read double */
    public double d(){
        return Double.longBitsToDouble(l());
    }

    /** read string ({@code readUTF}) */
    public String str(){
        return str(0);
    }

    /**
     * read string ({@code readUTF}), throwing {@link StringTooLongException} before reading the bytes if its encoded
     * length is over {@code maxLen} (when positive).
     */
    public String str(int maxLen){
        int utflen = us();
        if(maxLen > 0 && utflen > maxLen) throw new StringTooLongException(utflen, maxLen);
        need(utflen);
        int start = position;
        char[] chars = new char[utflen];
        int count = 0, length = 0;
        while(count < utflen){
            int c = bytes[start + count] & 0xFF;
            if(c > 127) break;
            count++;
            chars[length++] = (char)c;
        }
        while(count < utflen){
            int c = bytes[start + count] & 0xFF;
            switch(c >> 4){
                case 0, 1, 2, 3, 4, 5, 6, 7 -> {
                    count++;
                    chars[length++] = (char)c;
                }
                case 12, 13 -> {
                    count += 2;
                    if(count > utflen) throw malformed("malformed input: partial character at end");
                    int char2 = bytes[start + count - 1];
                    if((char2 & 0xC0) != 0x80) throw malformed("malformed input around byte " + count);
                    chars[length++] = (char)((c & 0x1F) << 6 | char2 & 0x3F);
                }
                case 14 -> {
                    count += 3;
                    if(count > utflen) throw malformed("malformed input: partial character at end");
                    int char2 = bytes[start + count - 2], char3 = bytes[start + count - 1];
                    if((char2 & 0xC0) != 0x80 || (char3 & 0xC0) != 0x80){
                        throw malformed("malformed input around byte " + (count - 1));
                    }
                    chars[length++] = (char)((c & 0x0F) << 12 | (char2 & 0x3F) << 6 | char3 & 0x3F);
                }
                default -> throw malformed("malformed input around byte " + count);
            }
        }
        position += utflen;
        return new String(chars, 0, length);
    }

    /** Skips up to {@code amount} bytes, stopping at the end; a negative amount skips nothing. */
    public void skip(int amount){
        if(amount > 0) position += Math.min(amount, limit - position);
    }

    /** Offset of the next byte in the backing array. */
    public int position(){
        return position;
    }

    public int remaining(){
        return limit - position;
    }

    /** A second reader from this one's position to its limit, to read ahead without moving this one. */
    public Reads fork(){
        return new Reads(bytes, position, limit - position);
    }

    private void need(int count){
        if(count > limit - position){
            throw new UncheckedIOException(new EOFException(
                "needs " + count + " bytes at offset " + position + ", " + (limit - position) + " left"));
        }
    }

    private static UncheckedIOException malformed(String message){
        return new UncheckedIOException(new UTFDataFormatException(message));
    }
}
