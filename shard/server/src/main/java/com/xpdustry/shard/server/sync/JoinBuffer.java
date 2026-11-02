package com.xpdustry.shard.server.sync;

import com.xpdustry.shard.net.*;

import java.util.*;

/**
 * The frames a loading viewer missed since its world stream was captured (ARCHITECTURE.md §7, join step 3), in send
 * order, bounded in count and bytes. It belongs to one load: a newer capture of the same session replaces it.
 */
final class JoinBuffer{
    /** The load generation it belongs to. */
    final int load;
    private final int maxFrames;
    private final long maxBytes;
    private final ArrayList<Frame> frames = new ArrayList<>();
    private long bytes;

    JoinBuffer(int load, int maxFrames, long maxBytes){
        this.load = load;
        this.maxFrames = maxFrames;
        this.maxBytes = maxBytes;
    }

    /** False, keeping nothing, if {@code frame} would go over a bound. */
    boolean add(Frame frame){
        if(frames.size() >= maxFrames || bytes + frame.size() > maxBytes) return false;
        frames.add(frame);
        bytes += frame.size();
        return true;
    }

    List<Frame> frames(){
        return frames;
    }

    long bytes(){
        return bytes;
    }
}
