package com.xpdustry.shard.compat.v160.sync;

import com.xpdustry.shard.common.io.*;

import java.util.*;

/**
 * Vanilla's snapshot batching ({@code NetServer.java:1153}): entries go into one buffer, and once an entry takes it
 * past {@link #MAX} bytes the buffer is sent with the count of its entries and starts over. A packet is therefore
 * {@link #MAX} bytes plus at most one entry, never less than one entry, and the last packet holds the rest.
 */
final class Batch{
    /** {@code NetServer.maxSnapshotSize}. */
    static final int MAX = 800;

    private final Writes out = new Writes(MAX + 256);
    private final ArrayList<Chunk> chunks = new ArrayList<>();
    private int count;

    /** One packet's worth: {@code amount} entries in {@code data}. */
    record Chunk(int amount, byte[] data){
    }

    /** Where the next entry is written. */
    Writes out(){
        return out;
    }

    /** Ends the entry just written: vanilla's {@code sent++}, then the size check. */
    void end(){
        count++;
        if(out.size() > MAX) flush();
    }

    /** The packets, the residual one included. */
    List<Chunk> finish(){
        if(count > 0) flush();
        return chunks;
    }

    private void flush(){
        chunks.add(new Chunk(count, out.toByteArray()));
        out.reset();
        count = 0;
    }
}
