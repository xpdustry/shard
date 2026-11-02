package com.xpdustry.shard.sim.world;

import java.util.*;

/**
 * An external observer's view of a world: a bounded queue of {@link WorldEvent}s, filled by the world after each
 * tick commits and drained by the observer on its own thread (ARCHITECTURE.md §4 "Plugins and observers"). The world
 * never blocks on it:
 * <ul>
 *   <li>A full queue drops new events and counts them in {@link #dropped()}, so the observer learns it missed
 *   some.</li>
 *   <li>An observer may subscribe to some event types only; the world tests them with {@code isInstance}, so no
 *   observer code runs on the world thread.</li>
 *   <li>An optional wake-up runs on the world thread after a tick delivered something. It must not block: unpark a
 *   thread or start a virtual one.</li>
 *   <li>Events of a tick that throws are never delivered: only committed ticks publish.</li>
 * </ul>
 * An observer that acts sends a {@link Command} for a future tick. Observers are {@link Persistence#EXTERNAL}: a fork
 * has none. {@link #close()} unsubscribes, from any thread.
 */
public final class Observer{
    private final int capacity;
    private final List<Class<? extends WorldEvent>> types;
    private final Runnable wake;
    private final ArrayDeque<WorldEvent> queue;
    private final ArrayList<WorldEvent> staged = new ArrayList<>();
    private long missed;
    private long dropped;
    private volatile boolean closed;

    Observer(int capacity, List<Class<? extends WorldEvent>> types, Runnable wake){
        if(capacity <= 0) throw new IllegalArgumentException("capacity must be positive: " + capacity);
        this.capacity = capacity;
        this.types = List.copyOf(types);
        this.wake = wake;
        this.queue = new ArrayDeque<>(Math.min(capacity, 1024));
    }

    /**
     * Holds an event of the running tick, if this observer wants it, until the tick commits. At most a queue's worth
     * is held per tick; the rest are counted as dropped. World thread only.
     */
    void stage(WorldEvent event){
        if(!wants(event)) return;
        if(staged.size() < capacity) staged.add(event);
        else missed++;
    }

    /** Moves the committed tick's events into the queue, then wakes the observer. World thread only. */
    void commit(){
        if(staged.isEmpty() && missed == 0) return;
        boolean added = false;
        synchronized(this){
            dropped += missed;
            for(WorldEvent event : staged){
                if(queue.size() >= capacity){
                    dropped++;
                }else{
                    queue.add(event);
                    added = true;
                }
            }
        }
        staged.clear();
        missed = 0;
        if(added && wake != null) wake.run();
    }

    /** Forgets the events of a tick that threw. World thread only. */
    void discard(){
        staged.clear();
        missed = 0;
    }

    private boolean wants(WorldEvent event){
        if(types.isEmpty()) return true;
        for(Class<? extends WorldEvent> type : types){
            if(type.isInstance(event)) return true;
        }
        return false;
    }

    /** The next event, or null if none is queued. */
    public synchronized WorldEvent poll(){
        return queue.poll();
    }

    /** Takes every queued event, oldest first. */
    public synchronized List<WorldEvent> drain(){
        var out = List.copyOf(queue);
        queue.clear();
        return out;
    }

    /** Events dropped because this queue was full, or a single tick published more than it holds. */
    public synchronized long dropped(){
        return dropped;
    }

    public int capacity(){
        return capacity;
    }

    /** The event types this observer receives; empty for all. */
    public List<Class<? extends WorldEvent>> types(){
        return types;
    }

    /** Unsubscribes; the world forgets this observer at its next delivery. Queued events can still be drained. */
    public void close(){
        closed = true;
    }

    public boolean closed(){
        return closed;
    }
}
