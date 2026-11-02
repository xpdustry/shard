package com.xpdustry.shard.server.sched;

import java.util.concurrent.locks.*;

/**
 * Monotonic time for the scheduler and the server's timed rules (deadlines, lateness, catch-up, cooldowns), and the
 * waits measured in it. {@link #SYSTEM} is {@link System#nanoTime}. A {@link ManualClock} moves only when a test moves
 * it, so what such a rule decides doesn't depend on how busy the machine is.
 */
public interface NanoClock{
    NanoClock SYSTEM = new NanoClock(){
        @Override
        public long nanoTime(){
            return System.nanoTime();
        }

        @Override
        public void awaitNanos(Condition condition, long nanos) throws InterruptedException{
            condition.awaitNanos(nanos);
        }

        @Override
        public void parkNanos(Object blocker, long nanos){
            LockSupport.parkNanos(blocker, nanos);
        }
    };

    long nanoTime();

    /**
     * Waits on {@code condition}, whose lock the caller holds, until it is signalled or about {@code nanos} of this
     * clock pass. It may return early: callers check the time again, as with {@link Condition#awaitNanos}.
     */
    void awaitNanos(Condition condition, long nanos) throws InterruptedException;

    /** Parks the calling thread for about {@code nanos} of this clock; it may return early, or on an unpark. */
    void parkNanos(Object blocker, long nanos);

    /**
     * Names {@code thread}, before it starts, as one that parks on this clock and acts on the time it reads (a
     * watchdog). A {@link ManualClock} counts it from now on, so a test can't move the time past it before it first runs.
     */
    default void addWatcher(Thread thread){
    }

    /** Sleeps {@code nanos} of this clock: for work that takes time, in tests. */
    default void sleepNanos(long nanos){
        long end = nanoTime() + nanos;
        for(long left; (left = end - nanoTime()) > 0; ) parkNanos(this, left);
    }
}
