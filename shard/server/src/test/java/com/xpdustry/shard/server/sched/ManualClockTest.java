package com.xpdustry.shard.server.sched;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/** {@link ManualClock#observed}: a watcher has seen the time once it has read it and parked again. */
class ManualClockTest{
    @Test
    void aWatcherHasSeenTheTimeOnceItHasReadItAndParked(){
        var clock = new ManualClock();
        assertTrue(clock.observed(), "nobody watches");
        clock.parkNanos(this, 1);
        assertFalse(clock.observed(), "watched from its first park, and it hasn't read the time");
        clock.nanoTime();
        assertFalse(clock.observed(), "it has read the time but may still be acting on it");
        clock.parkNanos(this, 1);
        assertTrue(clock.observed());
        clock.advance(1);
        assertFalse(clock.observed(), "the time moved");
        clock.parkNanos(this, 1);
        assertFalse(clock.observed(), "it parked without reading the new time");
        clock.nanoTime();
        clock.parkNanos(this, 1);
        assertTrue(clock.observed());
    }

    @Test
    void aWatcherNamedBeforeItStartsHoldsTheClockUntilItLooks() throws InterruptedException{
        var clock = new ManualClock();
        Thread watcher = Thread.ofPlatform().unstarted(() -> {
            clock.nanoTime();
            clock.parkNanos(this, 1);
        });
        clock.addWatcher(watcher);
        assertFalse(clock.observed(), "it hasn't run yet");
        watcher.start();
        watcher.join();
        assertTrue(clock.observed(), "it read the time and parked");
        clock.advance(1);
        assertTrue(clock.observed(), "a watcher that has ended holds nothing back");
    }
}
