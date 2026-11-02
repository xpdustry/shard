package com.xpdustry.shard.sim.persist;

import java.util.*;
import java.util.zip.*;

/**
 * How checkpoint sections are compressed, each on its own; a section that doesn't shrink is stored raw. JDK DEFLATE
 * only, so {@code :sim} needs no native code. Level 1 is the default, chosen by measurement on a 500×500 world with
 * 20k buildings (W1.4 report; {@code CheckpointBench}): higher levels cost several times the CPU for a few percent
 * of size, and compression runs off the tick thread anyway.
 *
 * @param codec {@link #RAW} or {@link #DEFLATE}
 * @param level the DEFLATE level, 1 to 9
 */
public record Compression(int codec, int level){
    static final int RAW = 0, DEFLATE = 1;

    public static final Compression NONE = new Compression(RAW, 0);
    public static final Compression FAST = new Compression(DEFLATE, 1);

    public Compression{
        if(codec != RAW && codec != DEFLATE) throw new IllegalArgumentException("unknown codec " + codec);
        if(codec == DEFLATE && (level < 1 || level > 9)) throw new IllegalArgumentException("DEFLATE level " + level);
    }

    public static Compression deflate(int level){
        return new Compression(DEFLATE, level);
    }

    /** The compressed bytes, or null when they wouldn't be smaller. */
    byte[] compress(byte[] raw, int length){
        if(codec == RAW || length == 0) return null;
        var deflater = new Deflater(level, true);
        try{
            deflater.setInput(raw, 0, length);
            deflater.finish();
            var out = new byte[Math.max(64, length / 4)];
            int size = 0;
            while(!deflater.finished()){
                if(size == out.length){
                    if(size >= length) return null;
                    out = Arrays.copyOf(out, Math.min(length, size * 2));
                }
                size += deflater.deflate(out, size, out.length - size);
            }
            return size < length ? Arrays.copyOf(out, size) : null;
        }finally{
            deflater.end();
        }
    }

    static byte[] decompress(int codec, byte[] stored, int offset, int length, int rawLength, String section){
        return switch(codec){
            case RAW -> {
                if(length != rawLength) throw new CheckpointException("section " + section + " is raw but its lengths differ");
                yield Arrays.copyOfRange(stored, offset, offset + length);
            }
            case DEFLATE -> {
                var inflater = new Inflater(true);
                try{
                    inflater.setInput(stored, offset, length);
                    var out = new byte[rawLength];
                    int size = 0;
                    while(size < rawLength){
                        int n = inflater.inflate(out, size, rawLength - size);
                        if(n == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) break;
                        size += n;
                    }
                    // The raw CRC is checked next, so a short or wrong stream can't pass.
                    if(size != rawLength) throw new CheckpointException("section " + section + " inflates short");
                    yield out;
                }catch(DataFormatException e){
                    throw new CheckpointException("section " + section + " doesn't inflate", e);
                }finally{
                    inflater.end();
                }
            }
            default -> throw new CheckpointException("section " + section + " uses unknown codec " + codec);
        };
    }
}
