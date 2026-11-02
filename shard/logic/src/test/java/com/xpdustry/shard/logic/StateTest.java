package com.xpdustry.shard.logic;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.io.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static com.xpdustry.shard.logic.TestHost.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Live state ({@link LogicState}): a world and its processors written mid-program and read back, or copied, go on
 * exactly as the originals, parked or not, with their generator, throttle, links and waits.
 */
class StateTest{
    /** Random sums into a cell, a switch toggled, and a wait that parks the processor between rounds. */
    static final String PROGRAM = """
        op rand r 100
        op add n n r
        write n cell1 0
        read m cell1 0
        print m
        sensor e switch1 @enabled
        op equal e e false
        control enabled switch1 e
        setrate 3
        wait 0.3
        jump 0 always
        """;

    /** A world with a processor linked to a cell and a switch. */
    record Scene(TestHost host, Processor p, Building cell, Building toggle){
        static Scene of(){
            TestHost host = new TestHost();
            Building cell = host.place(TestHost.cell, 2, 0), toggle = host.place(TestHost.toggle, 3, 0);
            Processor p = host.processor(0, 0, PROGRAM, true, 8, 1000);
            p.toggleLink(cell);
            p.toggleLink(toggle);
            return new Scene(host, p, cell, toggle);
        }

        /** The same buildings, without a processor yet. */
        Scene empty(){
            TestHost other = new TestHost();
            Building c = other.place(TestHost.cell, 2, 0), t = other.place(TestHost.toggle, 3, 0);
            System.arraycopy(cell.memory, 0, c.memory, 0, cell.memory.length);
            t.enabled = toggle.enabled;
            other.place(TestHost.processor, 0, 0);
            return new Scene(other, null, c, t);
        }

        Building at(Object original){
            var b = (Building)original;
            return (Building)host.building(b.x, b.y);
        }

        Building processorBuilding(){
            return (Building)host.building(0, 0);
        }

        /** This scene running {@code p}, its processor building's. */
        Scene with(Processor p){
            processorBuilding().processor = p;
            return new Scene(host, p, cell, toggle);
        }

        void run(int ticks){
            host.run(ticks, p);
        }

        /** The state as text: host objects by what they are, so two worlds compare. */
        String state(){
            var out = new Text();
            host.world.writeState(out);
            p.writeState(out);
            return out + " cell " + Arrays.toString(cell.memory) + " switch " + toggle.enabled;
        }
    }

    static final class Text implements LogicState.Out{
        final StringBuilder out = new StringBuilder();

        @Override
        public void bool(boolean value){
            out.append(value).append(' ');
        }

        @Override
        public void i(int value){
            out.append(value).append(' ');
        }

        @Override
        public void l(long value){
            out.append(value).append(' ');
        }

        @Override
        public void f(float value){
            out.append(Float.floatToRawIntBits(value)).append(' ');
        }

        @Override
        public void d(double value){
            out.append(Double.doubleToRawLongBits(value)).append(' ');
        }

        @Override
        public void str(String value){
            out.append('"').append(value).append("\" ");
        }

        @Override
        public void host(Object value){
            out.append('<').append(value).append("> ");
        }

        @Override
        public String toString(){
            return out.toString();
        }
    }

    @Test
    void aRestoredWorldContinuesLikeTheOriginal(){
        for(int at : new int[]{1, 7, 25, 40}){
            Scene a = Scene.of();
            a.run(at);

            var bytes = new Writes();
            var objects = new ArrayList<Object>();
            var writer = new LogicState.Writer(bytes, objects);
            a.host.world.writeState(writer);
            a.p.writeState(writer);

            Scene empty = a.empty();
            var reader = new LogicState.Reader(new Reads(bytes.toByteArray()), objects.stream().map(empty::at).toList());
            empty.host.world.readState(reader);
            Scene b = empty.with(Processor.readState(empty.host.world, empty.processorBuilding(), true, 8, 1000, Point2.pack(0, 0), 0, 0, reader));
            assertEquals(a.p.parked(), b.p.parked(), "parked at tick " + at);
            assertEquals(a.state(), b.state(), "restored at tick " + at);

            int calls = a.host.calls.size();
            for(int t = 0; t < 120; t++){
                a.run(1);
                b.run(1);
                assertEquals(a.state(), b.state(), "restored at tick " + at + ", then tick " + t);
            }
            assertEquals(a.host.calls.subList(calls, a.host.calls.size()), b.host.calls, "restored at tick " + at);
        }
    }

    @Test
    void theRestoreCoversAParkedProcessor(){
        Scene a = Scene.of();
        a.run(25);
        assertTrue(a.p.parked(), "mid-wait, the processor is parked");
        long parked = a.host.world.parkedCalls();
        a.run(5);
        assertTrue(a.host.world.parkedCalls() > parked, "and only counts its calls");
    }

    @Test
    void aSaveOfAParkedProcessorChangesNothing(){
        Scene a = Scene.of(), b = Scene.of(), materialized = Scene.of();
        a.run(25);
        b.run(25);
        materialized.run(25);
        assertTrue(a.p.parked(), "mid-wait, the processor is parked");
        LogicSave save = a.p.save();
        materialized.p.materialize();
        LogicSave expected = materialized.p.save();
        assertEquals(expected.accumulator(), save.accumulator(), "the save is of the state as vanilla's");
        assertArrayEquals(expected.waitTimes(), save.waitTimes(), "the save is of the state as vanilla's");
        assertTrue(a.p.parked(), "a save doesn't unpark");
        for(int t = 0; t < 120; t++){
            assertEquals(b.state(), a.state(), "tick " + t + " after the save");
            a.run(1);
            b.run(1);
        }
    }

    @Test
    void aCopyContinuesLikeTheOriginal(){
        Scene a = Scene.of();
        a.run(25);
        Scene empty = a.empty();
        empty.host.world = a.host.world.copy(empty.host);
        Scene b = empty.with(a.p.copy(empty.host.world, empty.processorBuilding(), empty::at));
        assertEquals(a.state(), b.state());
        for(int t = 0; t < 120; t++){
            a.run(1);
            b.run(1);
            assertEquals(a.state(), b.state(), "tick " + t);
        }
        // each follows its own world's links
        assertSame(a.cell, a.p.link(0));
        assertSame(b.cell, b.p.link(0));
    }

    @Test
    void aProcessorHasNoStateWhileItsSaveLoads(){
        Scene a = Scene.of();
        a.run(3);
        var loading = new Processor(a.host.world, a.p.building(), true, 8, 1000, Point2.pack(0, 0), 0, 0);
        loading.load(a.p.save(), 5);
        assertThrows(IllegalStateException.class, () -> loading.writeState(new Text()));
        assertThrows(IllegalStateException.class, () -> loading.copy(a.host.world, a.p.building(), value -> value));
        loading.checkReadCode();
        loading.writeState(new Text());
    }

    @Test
    void scriptsRunOnceOrLoopUpToTheirLimit(){
        TestHost host = new TestHost();
        Processor once = Processor.script(host.world, "op add i i 1");
        once.runScript(100, false);
        assertEquals(1, num(once, "i"));

        // runLogicScript's loop runs maxInstructions - 1 instructions
        Processor loop = Processor.script(host.world, "op add i i 1");
        loop.runScript(100, true);
        assertEquals(99, num(loop, "i"));

        // a stop or an unfinished wait yields, which ends the script
        Processor stop = Processor.script(host.world, "op add i i 1\nstop");
        stop.runScript(100, true);
        assertEquals(1, num(stop, "i"));
        Processor wait = Processor.script(host.world, "op add i i 1\nwait 1");
        wait.runScript(100, true);
        assertEquals(1, num(wait, "i"));

        // privileged, without a building or links, and @ipt is 0
        Processor globals = Processor.script(host.world, "set rate @ipt\nset me @this");
        globals.runScript(10, false);
        assertTrue(globals.privileged);
        assertEquals(0, num(globals, "rate"));
        assertNull(var(globals, "me"));
    }

    @Test
    void malformedCodeLoadsAsNoCodeAndKeepsTheLinks(){
        TestHost host = new TestHost();
        Building cell = host.place(TestHost.cell, 2, 0);
        Processor p = host.processor(0, 0, "write 1 cell1 0");
        p.toggleLink(cell);
        host.run(2, p);
        assertEquals(1, cell.memory[0]);

        p.setCode("write 2 cell1 0\nprint \"unclosed");
        assertEquals("", p.code());
        assertEquals(1, p.linkCount());
        host.run(2, p);
        assertEquals(1, cell.memory[0], "nothing runs");

        // a malformed script does nothing at all
        Processor script = Processor.script(host.world, "op add i i 1\nprint \"unclosed");
        script.runScript(100, true);
        assertEquals("", script.code());
        assertEquals(0, script.program().length);
    }
}
