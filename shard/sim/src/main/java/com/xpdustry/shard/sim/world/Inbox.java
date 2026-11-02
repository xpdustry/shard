package com.xpdustry.shard.sim.world;

import java.util.*;
import java.util.function.*;

/**
 * A world's bounded input queue: the only way into a world from another thread (ARCHITECTURE.md §4, step 2). Any
 * thread may {@link #offer(Input)}; the world drains it in {@link Phase#COMMANDS}. Arrival order is the order in
 * which offers succeed, stamped with an increasing arrival number, and inputs are admitted in that order.
 *
 * <p>A full inbox refuses the offer; the caller owns backpressure (the runtime's per-session mailboxes may coalesce
 * superseded input snapshots, never reliable commands). Pending inputs are {@link Persistence#EXTERNAL} to the
 * world's state: a fork starts with an empty inbox, and the hash never sees them. A checkpoint still stores them with
 * the next arrival number ({@link #snapshot()}), so a restored world admits what was waiting and its log goes on
 * numbering where the old one stopped.
 *
 * <p>Two hooks for the runtime: a {@link #signal(Runnable) signal} that runs when an offer finds the inbox empty
 * (the scheduler uses it to wake a world that ticks slowly or not at all), and {@link #seal()}, which refuses every
 * offer while a world is being hibernated, so nothing can arrive at a world that is about to leave memory.
 */
public final class Inbox{
    private final int capacity;
    private final ArrayDeque<Arrival> queue;
    private long arrivals;
    private long refused;
    private boolean sealed;
    private volatile Runnable signal;

    public Inbox(int capacity){
        if(capacity <= 0) throw new IllegalArgumentException("capacity must be positive: " + capacity);
        this.capacity = capacity;
        this.queue = new ArrayDeque<>(Math.min(capacity, 1024));
    }

    /** Queues {@code input}; false, and nothing queued, when the inbox is full or sealed. */
    public boolean offer(Input input){
        Objects.requireNonNull(input, "input");
        boolean first;
        synchronized(this){
            if(sealed || queue.size() >= capacity){
                refused++;
                return false;
            }
            first = queue.isEmpty();
            queue.add(new Arrival(arrivals++, input));
        }
        Runnable signal = this.signal;
        if(first && signal != null) signal.run();
        return true;
    }

    /** Hands every queued input to {@code action}, in arrival order, and empties the inbox. Returns how many. */
    public int drain(Consumer<Arrival> action){
        Arrival[] batch;
        synchronized(this){
            if(queue.isEmpty()) return 0;
            batch = queue.toArray(Arrival[]::new);
            queue.clear();
        }
        for(Arrival arrival : batch) action.accept(arrival);
        return batch.length;
    }

    /**
     * Runs {@code signal} on the offering thread whenever an offer finds the inbox empty; null removes it. The signal
     * must not block: it runs inside whatever thread offered.
     */
    public void signal(Runnable signal){
        this.signal = signal;
    }

    /** Refuses every offer from now on, until {@link #unseal()}. Inputs already queued stay. */
    public synchronized void seal(){
        sealed = true;
    }

    public synchronized void unseal(){
        sealed = false;
    }

    public synchronized boolean sealed(){
        return sealed;
    }

    public int capacity(){
        return capacity;
    }

    public synchronized int size(){
        return queue.size();
    }

    /** Offers refused because the inbox was full or sealed. */
    public synchronized long refused(){
        return refused;
    }

    /** The arrival number the next accepted offer gets: how many offers this world has ever accepted. */
    public synchronized long nextArrival(){
        return arrivals;
    }

    /**
     * The queued inputs, in arrival order, and the next arrival number, read at once: what a checkpoint stores, so a
     * restored world admits what was waiting and goes on numbering where this one was (W1.4).
     */
    public synchronized Snapshot snapshot(){
        return new Snapshot(arrivals, List.copyOf(queue));
    }

    /**
     * Takes over {@code snapshot}: its inputs are queued in front of anything queued since, and numbering continues
     * from its next arrival number, or this inbox's own if that is higher. A checkpoint's restore and a fork that is
     * about to be captured call it; the inputs keep their numbers.
     */
    public synchronized void restore(Snapshot snapshot){
        var waiting = new ArrayList<>(queue);
        queue.clear();
        queue.addAll(snapshot.pending());
        queue.addAll(waiting);
        arrivals = Math.max(arrivals, snapshot.nextArrival());
    }

    /**
     * Empties the inbox for a replay, which offers the logged inputs instead ({@link #replay}): numbering goes back to
     * the first input it held, as if none had arrived. Returns them.
     */
    public synchronized List<Arrival> forget(){
        var out = List.copyOf(queue);
        queue.clear();
        if(!out.isEmpty()) arrivals = out.getFirst().number();
        return out;
    }

    /**
     * Queues a logged input under the arrival number it had when it was recorded, which must not be below
     * {@link #nextArrival()}; numbering continues after it. Replays use it so the replayed log equals the recorded
     * one, numbers included. False when the inbox is full or sealed.
     */
    public synchronized boolean replay(Arrival arrival){
        Objects.requireNonNull(arrival, "arrival");
        if(arrival.number() < arrivals){
            throw new IllegalArgumentException("arrival " + arrival.number() + " is below the next number " + arrivals);
        }
        if(sealed || queue.size() >= capacity){
            refused++;
            return false;
        }
        queue.add(arrival);
        arrivals = arrival.number() + 1;
        return true;
    }

    /**
     * What an inbox holds at one moment ({@link #snapshot()}).
     *
     * @param nextArrival the number the next offer gets
     * @param pending the queued inputs, in arrival order
     */
    public record Snapshot(long nextArrival, List<Arrival> pending){
        public static final Snapshot EMPTY = new Snapshot(0, List.of());

        public Snapshot{
            pending = List.copyOf(pending);
        }
    }

    /** An input and its arrival number, which increases by one per accepted offer. */
    public record Arrival(long number, Input input){
    }
}
