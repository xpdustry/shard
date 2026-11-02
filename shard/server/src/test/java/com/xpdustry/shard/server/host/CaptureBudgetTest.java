package com.xpdustry.shard.server.host;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

class CaptureBudgetTest{
    final Object a = "a", b = "b";

    @Test
    void capturesAndStreamsReserveAgainstBothLimits(){
        var budget = new CaptureBudget(100, 60, 60, 8);
        CaptureBudget.Capture capture = budget.capture(a, 20);
        CaptureBudget.Stream one = capture.add(20), two = capture.add(20);
        assertNotNull(one);
        assertNotNull(two);
        assertNull(capture.add(20), "past a's 60");
        assertEquals(60, budget.reserved(a));
        CaptureBudget.Capture other = budget.capture(b, 30);
        assertNotNull(other.add(10));
        assertNull(other.add(10), "past the global 100");
        assertEquals(100, budget.reserved());
        assertEquals(2, budget.refused());

        capture.close();
        assertEquals(60, budget.reserved(a), "the capture goes back with its last stream");
        one.release();
        one.release();
        assertEquals(40, budget.reserved(a), "once");
        two.release();
        assertEquals(0, budget.reserved(a));
        assertEquals(40, budget.reserved());
    }

    @Test
    void noReservationEverPassesALimit(){
        var budget = new CaptureBudget(100, 50, 40, 8);
        assertNull(budget.capture(a, 51), "past a world's 50, even with nothing else reserved");
        CaptureBudget.Capture capture = budget.capture(a, 30);
        assertNull(capture.add(21));
        assertNotNull(capture.add(20));
        assertEquals(50, budget.reserved(a));
        assertEquals(40, budget.captureLimit(), "the most a capture with its first stream may hold");
        assertThrows(IllegalArgumentException.class, () -> new CaptureBudget(100, 50, 60, 8), "a capture's limit within a world's");
        assertThrows(IllegalArgumentException.class, () -> new CaptureBudget(40, 50, 40, 8), "a world's within the global one");
    }

    @Test
    void aResizeTrueUpsTheCaptureAndItsStreamsAtOnceOrNotAtAll(){
        var budget = new CaptureBudget(100, 60, 60, 8);
        CaptureBudget.Capture capture = budget.capture(a, 10);
        CaptureBudget.Stream one = capture.add(5), two = capture.add(5);
        assertEquals(20, budget.reserved(a));
        assertTrue(capture.resize(20, 15), "20 + 2 x 15 fits a's 60");
        assertEquals(50, budget.reserved(a));
        assertFalse(capture.resize(30, 20), "70 doesn't");
        assertEquals(50, budget.reserved(a), "nothing changed");
        assertTrue(capture.resize(4, 3), "shrinking always fits");
        assertEquals(10, budget.reserved(a));

        capture.close();
        one.release();
        two.release();
        assertEquals(0, budget.reserved(), "each part goes back at its new size");
        assertEquals(0, budget.inFlight());
    }

    @Test
    void encodesInFlightAreCapped(){
        var budget = new CaptureBudget(1000, 1000, 1000, 2);
        CaptureBudget.Capture capture = budget.capture(a, 1);
        CaptureBudget.Stream one = capture.add(1);
        assertNotNull(capture.add(1));
        assertNull(capture.add(1));
        assertEquals(2, budget.inFlight());
        one.release();
        assertNotNull(capture.add(1));
    }
}
