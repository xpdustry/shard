package com.xpdustry.shard.server.sched;

import java.util.*;
import java.util.concurrent.locks.*;

/**
 * A {@link NanoClock} that moves only by {@link #advance}: tests drive a {@link WorldScheduler} or a timed rule through
 * virtual time, so their outcome doesn't depend on how busy the machine is.
 *
 * <ul>
 *   <li>{@link #sleepNanos} is work that takes virtual time: the thread is busy until the clock reaches its end, and
 *   {@link #sleepers()} counts it meanwhile.</li>
 *   <li>Timed waits ({@link #awaitNanos}) poll the clock every {@value #POLL_NANOS} ns of real time, so only threads
 *   that wait for a deadline notice it pass; an untimed waiter still needs its signal, as with the system clock.</li>
 *   <li>A thread that {@link #parkNanos parks} on the clock (a watchdog), or that {@link #addWatcher} names before it
 *   starts, is watched: {@link #observed()} is true once each such thread has read the time since the last advance
 *   and parked again, so it has acted on that time.</li>
 * </ul>
 *
 * <p>Thread-safe.
 */
public final class ManualClock implements NanoClock{
    static final long POLL_NANOS = 200_000;

    /** Guarded by this. */
    private long now;
    private long advances;
    /** Threads in {@link #sleepNanos}, and when they wake. */
    private final HashMap<Thread, Long> sleeping = new HashMap<>();
    /** Threads that park on this clock, and the advance count of the read each last parked after. */
    private final HashMap<Thread, Long> watched = new HashMap<>();
    /** The advance count when each watched thread last read the time. */
    private final HashMap<Thread, Long> read = new HashMap<>();

    public ManualClock(){
    }

    @Override
    public long nanoTime(){
        Thread self = Thread.currentThread();
        synchronized(this){
            // seen only once it parks again: until then it may still be acting on the time before
            if(watched.containsKey(self)) read.put(self, advances);
            return now;
        }
    }

    /** Moves the time on and wakes the sleepers whose time has come. */
    public void advance(long nanos){
        if(nanos < 0) throw new IllegalArgumentException("time only moves on: " + nanos);
        List<Thread> wake = new ArrayList<>();
        synchronized(this){
            now += nanos;
            advances++;
            sleeping.forEach((thread, end) -> {
                if(end <= now) wake.add(thread);
            });
        }
        for(Thread thread : wake) LockSupport.unpark(thread);
    }

    /** Threads busy in {@link #sleepNanos} whose end hasn't come. */
    public synchronized int sleepers(){
        int count = 0;
        for(long end : sleeping.values()){
            if(end > now) count++;
        }
        return count;
    }

    /** When the next sleeper's end comes, or {@link Long#MAX_VALUE}. */
    public synchronized long nextWake(){
        long next = Long.MAX_VALUE;
        for(long end : sleeping.values()){
            if(end > now) next = Math.min(next, end);
        }
        return next;
    }

    /**
     * Whether every thread that parks on this clock has read the time since the last advance and parked again. A
     * watcher that has ended holds nothing back.
     */
    public synchronized boolean observed(){
        for(Map.Entry<Thread, Long> entry : watched.entrySet()){
            if(entry.getValue() != advances && entry.getKey().getState() != Thread.State.TERMINATED) return false;
        }
        return true;
    }

    @Override
    public void sleepNanos(long nanos){
        Thread self = Thread.currentThread();
        synchronized(this){
            sleeping.put(self, now + nanos);
        }
        try{
            while(true){
                synchronized(this){
                    if(now >= sleeping.get(self)) return;
                }
                LockSupport.parkNanos(this, POLL_NANOS);
            }
        }finally{
            synchronized(this){
                sleeping.remove(self);
            }
        }
    }

    @Override
    public void awaitNanos(Condition condition, long nanos) throws InterruptedException{
        condition.awaitNanos(POLL_NANOS);
    }

    @Override
    public synchronized void addWatcher(Thread thread){
        watched.putIfAbsent(thread, -1L);
    }

    @Override
    public void parkNanos(Object blocker, long nanos){
        Thread self = Thread.currentThread();
        synchronized(this){
            watched.put(self, read.getOrDefault(self, -1L));
        }
        LockSupport.parkNanos(blocker, POLL_NANOS);
    }

    @Override
    public synchronized String toString(){
        return "ManualClock[" + now + " ns]";
    }
}
