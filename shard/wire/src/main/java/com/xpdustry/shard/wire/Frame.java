package com.xpdustry.shard.wire;

import com.xpdustry.shard.common.io.*;

/**
 * A packet frame's header as vanilla's Arc transport serializes it (ArcNetProvider.java:507-580):
 * <pre>
 * u8 packet id | u16 body length, before compression | u8 compression: 0 none, else LZ4 block | body (LZ4 when compressed)
 * </pre>
 * TCP puts a u16 frame length in front; UDP sends the frame as one datagram. A first byte of {@code 0xFE} (-2) starts an
 * Arc framework message instead, which {@code :net} handles, as it does LZ4 and the sockets.
 *
 * @param length the body's length before compression
 */
public record Frame(int id, int length, boolean compressed){
    /** Header bytes before the body. */
    public static final int HEADER = 4;
    /** The first byte of an Arc framework message (ping, keepalive, UDP and TCP registration). */
    public static final int FRAMEWORK = 0xFE;
    /** Bodies shorter than this are sent uncompressed (ArcNetProvider.java:570). */
    public static final int COMPRESS_FROM = 36;
    /**
     * The longest body vanilla reads: it copies or decompresses bodies into a 32 KiB buffer (ArcNetProvider.java:483).
     * It writes one byte less: the length goes through a {@code short} (ArcNetProvider.java:565), so a full buffer fails.
     */
    public static final int MAX_BODY = 32768;
    /** The longest body vanilla writes. */
    public static final int MAX_WRITTEN = MAX_BODY - 1;
    /** {@code StreamChunk}, the one packet vanilla never compresses; its id is the same in every build. */
    public static final int STREAM_CHUNK = 1;

    public Frame{
        if(id < 0 || id > 0xFF || id == FRAMEWORK) throw new IllegalArgumentException("not a packet id: " + id);
        if(length < 0 || length > MAX_BODY) throw new IllegalArgumentException("body length " + length + " is outside 0.." + MAX_BODY);
    }

    /** The header vanilla writes for a body of {@code length} bytes: LZ4 from 36 bytes on, except stream chunks. */
    public static Frame of(int id, int length){
        if(length > MAX_WRITTEN) throw new IllegalArgumentException("vanilla writes at most " + MAX_WRITTEN + " body bytes, not " + length);
        return new Frame(id, length, length >= COMPRESS_FROM && id != STREAM_CHUNK);
    }

    public void write(Writes write){
        write.b(id);
        write.s(length);
        write.b(compressed ? 1 : 0);
    }

    /**
     * Reads a header. Vanilla treats any non-zero compression byte as LZ4, and so does this. Throws
     * {@link WireException} for a framework message, a body over {@link #MAX_BODY} or a header cut short.
     */
    public static Frame read(Reads read){
        if(read.remaining() < HEADER) throw new WireException("a frame header has " + HEADER + " bytes, " + read.remaining() + " left");
        int id = read.ub();
        if(id == FRAMEWORK) throw new WireException("a framework message, not a packet");
        int length = read.us();
        boolean compressed = read.b() != 0;
        if(length > MAX_BODY) throw new WireException("body of " + length + " bytes, over vanilla's " + MAX_BODY);
        return new Frame(id, length, compressed);
    }

    /** An uncompressed frame around {@code body}, as vanilla sends bodies under 36 bytes and stream chunks. */
    public static byte[] raw(int id, byte[] body){
        Writes write = new Writes(HEADER + body.length);
        new Frame(id, body.length, false).write(write);
        write.b(body);
        return write.toByteArray();
    }
}
