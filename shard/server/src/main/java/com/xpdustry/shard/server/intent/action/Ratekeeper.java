package com.xpdustry.shard.server.intent.action;

import com.xpdustry.shard.sim.world.*;

/**
 * Arc's {@code Ratekeeper}: at most {@code cap} actions per chunk of {@code spacing} milliseconds, where a chunk starts
 * at the first action after the last one ended. Vanilla reads {@code Time.millis()}; here the time is the packet's
 * recorded arrival, so a replay counts the same.
 */
public final class Ratekeeper{
    /** Actions in the current chunk. */
    public int occurrences;
    /** When the current chunk started; meaningless until {@link #started}. */
    public long lastTime;
    /** Vanilla's {@code lastTime} starts at 0, so its first action always starts a chunk. */
    public boolean started;

    public Ratekeeper(){
    }

    /** {@code allow(spacing, cap)} at time {@code now}, in milliseconds. */
    public boolean allow(long now, long spacing, int cap){
        if(!started || now - lastTime > spacing){
            occurrences = 0;
            lastTime = now;
            started = true;
        }
        occurrences++;
        return occurrences <= cap;
    }

    public Ratekeeper copy(){
        var out = new Ratekeeper();
        out.occurrences = occurrences;
        out.lastTime = lastTime;
        out.started = started;
        return out;
    }

    public void hash(Hasher hasher){
        hasher.i(occurrences).l(lastTime).bool(started);
    }
}
