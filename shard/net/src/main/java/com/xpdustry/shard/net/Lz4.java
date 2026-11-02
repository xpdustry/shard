package com.xpdustry.shard.net;

import java.util.*;

/**
 * The LZ4 raw block format that lz4-java's {@code fastCompressor} writes and Mindustry's {@code PacketSerializer} reads
 * (no frame header, no checksum). A greedy single-probe hash matcher, enough for packets of 36 B to 32 KiB; its output
 * differs from lz4-java's but decodes to the same bytes, so compare frames decompressed.
 *
 * <p>A compressor keeps a 16 KiB hash table, so give each thread or connection its own instance: never share one
 * between threads, and don't keep one per virtual thread in a {@code ThreadLocal} (note 24 §1.6).
 * {@link #decompress} is stateless and validates its input.
 */
public final class Lz4{
    private static final int MIN_MATCH = 4, MF_LIMIT = 12, LAST_LITERALS = 5, HASH_BITS = 12, MAX_OFFSET = 65535;
    private static final ThreadLocal<Lz4> PLATFORM = ThreadLocal.withInitial(Lz4::new);

    private final int[] table = new int[1 << HASH_BITS];

    /**
     * A compressor for the current thread: a cached one on platform threads (tick and fan-out threads), a new one on
     * a virtual thread. Callers on virtual threads that compress often should keep their own instance.
     */
    public static Lz4 forThread(){
        return Thread.currentThread().isVirtual() ? new Lz4() : PLATFORM.get();
    }

    /** Worst-case compressed size, as {@code LZ4_compressBound}. */
    public static int bound(int length){
        return length + length / 255 + 16;
    }

    /** Compresses {@code src[off, off + len)} into {@code dst} at {@code dOff}; returns the number of bytes written. */
    public int compress(byte[] src, int off, int len, byte[] dst, int dOff){
        Objects.checkFromIndexSize(off, len, src.length);
        Objects.checkFromIndexSize(dOff, bound(len), dst.length);
        Arrays.fill(table, 0);
        int end = off + len, anchor = off, op = dOff;

        if(len >= MF_LIMIT + 1){
            int limit = end - MF_LIMIT, matchLimit = end - LAST_LITERALS;
            int ip = off + 1, step = 1 << 6;
            table[hash(src, off)] = 1;

            while(ip < limit){
                int h = hash(src, ip);
                int ref = table[h] - 1 + off;
                table[h] = ip - off + 1;

                if(ref < off || ip - ref > MAX_OFFSET || readInt(src, ref) != readInt(src, ip)){
                    // skip faster through incompressible runs, as the reference implementation does
                    ip += step++ >>> 6;
                    continue;
                }
                step = 1 << 6;

                // extend backwards over literals that also match
                while(ip > anchor && ref > off && src[ip - 1] == src[ref - 1]){
                    ip--;
                    ref--;
                }

                int match = MIN_MATCH;
                while(ip + match < matchLimit && src[ip + match] == src[ref + match]) match++;

                op = writeSequence(src, anchor, ip - anchor, dst, op, ip - ref, match);
                ip += match;
                anchor = ip;
                if(ip < limit) table[hash(src, ip - 2)] = ip - 2 - off + 1;
            }
        }

        int literals = end - anchor;
        dst[op++] = (byte)(Math.min(literals, 15) << 4);
        op = writeLength(dst, op, literals);
        System.arraycopy(src, anchor, dst, op, literals);
        return op + literals - dOff;
    }

    /**
     * Decompresses the block {@code src[off, off + len)}, which must decode to exactly {@code outLength} bytes.
     *
     * @throws IllegalArgumentException if the block is malformed or decodes to another length
     */
    public static byte[] decompress(byte[] src, int off, int len, int outLength){
        Objects.checkFromIndexSize(off, len, src.length);
        byte[] out = new byte[outLength];
        int sp = off, end = off + len, dp = 0;

        while(sp < end){
            int token = src[sp++] & 0xff;
            int literals = token >>> 4;
            if(literals == 15){
                int b;
                do{
                    if(sp >= end) throw corrupt("literal length past the end");
                    b = src[sp++] & 0xff;
                    literals += b;
                }while(b == 255);
            }
            if(literals > end - sp || literals > outLength - dp) throw corrupt("literals overrun");
            System.arraycopy(src, sp, out, dp, literals);
            sp += literals;
            dp += literals;
            if(sp == end) break;

            if(end - sp < 2) throw corrupt("truncated match offset");
            int offset = (src[sp++] & 0xff) | (src[sp++] & 0xff) << 8;
            int match = token & 15;
            if(match == 15){
                int b;
                do{
                    if(sp >= end) throw corrupt("match length past the end");
                    b = src[sp++] & 0xff;
                    match += b;
                }while(b == 255);
            }
            match += MIN_MATCH;
            if(offset == 0 || offset > dp) throw corrupt("match offset " + offset + " at " + dp);
            if(match > outLength - dp) throw corrupt("match overruns the output");
            for(int i = 0; i < match; i++, dp++) out[dp] = out[dp - offset];
        }

        if(dp != outLength) throw corrupt("expected " + outLength + " bytes, got " + dp);
        return out;
    }

    private static IllegalArgumentException corrupt(String why){
        return new IllegalArgumentException("LZ4: corrupt block: " + why);
    }

    private static int writeSequence(byte[] src, int litStart, int literals, byte[] dst, int op, int offset, int match){
        int ml = match - MIN_MATCH;
        dst[op++] = (byte)(Math.min(literals, 15) << 4 | Math.min(ml, 15));
        op = writeLength(dst, op, literals);
        System.arraycopy(src, litStart, dst, op, literals);
        op += literals;
        dst[op++] = (byte)offset;
        dst[op++] = (byte)(offset >>> 8);
        return writeLength(dst, op, ml);
    }

    private static int writeLength(byte[] dst, int op, int length){
        if(length < 15) return op;
        length -= 15;
        while(length >= 255){
            dst[op++] = (byte)255;
            length -= 255;
        }
        dst[op++] = (byte)length;
        return op;
    }

    private static int hash(byte[] b, int i){
        return (readInt(b, i) * -1640531535) >>> (32 - HASH_BITS);
    }

    private static int readInt(byte[] b, int i){
        return (b[i] & 0xff) | (b[i + 1] & 0xff) << 8 | (b[i + 2] & 0xff) << 16 | (b[i + 3] & 0xff) << 24;
    }
}
