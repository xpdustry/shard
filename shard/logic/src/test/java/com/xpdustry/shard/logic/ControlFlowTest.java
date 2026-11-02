package com.xpdustry.shard.logic;

import org.junit.jupiter.api.*;

import static com.xpdustry.shard.logic.TestHost.*;
import static org.junit.jupiter.api.Assertions.*;

/** Jumps, {@code @counter}, {@code end}, {@code wait}, {@code stop}, the accumulator, and parking. */
class ControlFlowTest{
    final TestHost host = new TestHost();

    // a processor's first tick only fills its accumulator, so programs start on the second

    @Test
    void fractionalCounter(){
        Processor p = host.processor(0, 0, """
            set @counter 2.5
            print 1
            print 2
            print 3
            stop
            """);
        host.run(2, p);
        assertEquals("23", p.text().toString());
    }

    @Test
    void counterIsReadable(){
        Processor p = host.processor(0, 0, """
            noop
            set a @counter
            op add b @counter 0
            stop
            """);
        host.run(2, p);
        assertEquals(2, num(p, "a"));
        assertEquals(3, num(p, "b"));
        assertEquals(3.0, p.counterValue(), "stop rewinds onto itself");
    }

    @Test
    void outOfRangeJumpsWrapOnTheNextInstruction(){
        Processor p = host.processor(0, 0, """
            op add n n 1
            jump 40 always
            """);
        host.run(2, p);
        // 8 instructions: 4 adds; the counter sits at 40 between ticks
        assertEquals(4, num(p, "n"));
        assertEquals(40.0, p.counterValue());
    }

    @Test
    void jumpMinusOneNeverJumps(){
        Processor p = host.processor(0, 0, "jump -1 always\nset x 1\nstop");
        host.run(2, p);
        assertEquals(1, num(p, "x"));
    }

    @Test
    void objectCounterKeepsItsNumber(){
        Processor p = host.processor(0, 0, """
            op add n n 1
            set @counter "s"
            """);
        host.run(2, p);
        // the counter's number stays 2 after the object write, so the program wraps: 4 rounds of 2
        assertEquals(4, num(p, "n"));
        assertEquals("s", p.counterValue());
    }

    @Test
    void endRestarts(){
        Processor p = host.processor(0, 0, "op add n n 1\nend\nset never 1");
        host.run(3, p);
        assertEquals(8, num(p, "n"));
        assertNull(var(p, "never"));
    }

    @Test
    void accumulatorCapsAtFiveTicks(){
        Processor p = host.processor(0, 0, "stop");
        host.run(20, p);
        // capped at 5 * ipt before the loop, then this tick's share is added
        assertEquals(48f, p.accumulator());
    }

    @Test
    void stopHoldsForever(){
        Processor p = host.processor(0, 0, "op add n n 1\nstop");
        host.run(500, p);
        assertEquals(1, num(p, "n"));
        assertEquals(1, p.executed(), "a stop is not charged");
    }

    @Test
    void waitTakesSeconds(){
        Processor p = host.processor(0, 0, "op add n n 1\nwait 0.5");
        host.run(90, p);
        // 0.5 s is 30 ticks of 1/60 each, plus the tick that sees it done
        assertEquals(3, num(p, "n"));
    }

    @Test
    void waitZeroYieldsOnce(){
        Processor p = host.processor(0, 0, "op add n n 1\nwait 0");
        host.run(10, p);
        assertEquals(9, num(p, "n"));
    }

    /** Parked processors only count ticks; observing one replays them. Both must equal a processor that never parks. */
    @Test
    void parkingIsInvisible(){
        String code = """
            op add n n 1
            wait 0.37
            op add m m 1
            jump 0 lessThan m 3
            wait 2
            op rand r 1 0
            """;
        TestHost watched = new TestHost(), parked = new TestHost();
        Processor a = watched.processor(0, 0, code), b = parked.processor(0, 0, code);
        for(int t = 0; t < 2000; t++){
            watched.run(1, a);
            parked.run(1, b);
            a.accumulator(); // observes every tick, so it never stays parked
            if(t % 97 == 0){
                assertEquals(a.save().accumulator(), b.save().accumulator(), "tick " + t);
                assertArrayEquals(a.save().waitTimes(), b.save().waitTimes(), "tick " + t);
                assertEquals(a.save().variables(), b.save().variables(), "tick " + t);
            }
        }
        assertTrue(parked.world.parkedCalls() > 1000, "parked " + parked.world.parkedCalls());
    }

    @Test
    void externalWritesWakeParkedProcessors(){
        Processor sleeper = host.processor(0, 0, "op add n n 1\nwait t");
        Processor writer = host.processor(1, 0, "stop");
        sleeper.setCode("set t 1000\nop add n n 1\nwait t");
        host.run(5, sleeper);
        assertTrue(sleeper.parked());
        writer.setCode("write 0 processor1 \"t\"\nstop");
        writer.toggleLink(host.building(0, 0));
        host.run(2, writer);
        assertFalse(sleeper.parked(), "a write unparks");
        host.run(2, sleeper);
        assertEquals(2, num(sleeper, "n"));
    }
}
