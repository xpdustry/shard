package com.xpdustry.shard.logic;

import org.junit.jupiter.api.*;

import static com.xpdustry.shard.logic.TestHost.*;
import static org.junit.jupiter.api.Assertions.*;

/** The save state round-trips: links, variables, the raw counter, waits, ipt, tag and accumulator. */
class SaveTest{
    @Test
    void roundTrip(){
        TestHost host = new TestHost();
        Building cell = host.place(TestHost.cell, 2, 0);
        Processor p = host.processor(0, 0, """
            set s "text"
            set c cell1
            op add n n 0.25
            setrate 5
            set @counter 5.5
            noop
            wait 0.4
            jump 2 always
            """, true, 8, 1000);
        p.toggleLink(cell);
        p.tag = "tag";
        p.iconTag = 'x';
        Object unit = new Object();
        p.bindUnit(unit);
        host.run(37, p);
        LogicSave save = p.save();

        assertEquals("@unit", save.variables().getFirst().name());
        assertEquals(5, save.ipt());
        assertEquals(1, save.waitIndices().length);
        assertEquals(6, save.waitIndices()[0]);

        Processor copy = new Processor(host.world, p.building(), true, 8, 1000, com.xpdustry.shard.common.geom.Point2.pack(0, 0), 0, 0);
        copy.load(save, 5);
        copy.checkReadCode();
        LogicSave again = copy.save();
        assertEquals(save.code(), again.code());
        assertEquals(save.links(), again.links());
        assertEquals(save.variables(), again.variables());
        assertEquals(save.ipt(), again.ipt());
        assertEquals("tag", again.tag());
        assertEquals('x', again.iconTag());
        assertArrayEquals(save.waitIndices(), again.waitIndices());
        assertArrayEquals(save.waitTimes(), again.waitTimes());
        assertEquals(save.accumulator(), again.accumulator());
        assertSame(unit, copy.unit());
        assertEquals(p.counterValue(), copy.counterValue());

        // both go on identically
        host.run(50, p, copy);
        assertEquals(p.save().variables(), copy.save().variables());
    }

    @Test
    void normalProcessorsSaveTheirDefaultRateAsZero(){
        TestHost host = new TestHost();
        Processor p = host.processor(0, 0, "stop");
        assertEquals(0, p.save().ipt());
        p.ipt(3);
        assertEquals(3, p.save().ipt());
    }
}
