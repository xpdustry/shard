package com.xpdustry.shard.logic;

import com.xpdustry.shard.content.*;
import org.junit.jupiter.api.*;

import static com.xpdustry.shard.logic.TestHost.*;
import static org.junit.jupiter.api.Assertions.*;

/** Links, memory, {@code sensor}, {@code control}, {@code lookup}, {@code setrate} and other processors. */
class WorldTest{
    final TestHost host = new TestHost();

    @Test
    void linkNames(){
        assertEquals("cell", Processor.linkName("memory-cell"));
        assertEquals("display", Processor.linkName("large-logic-display"));
        assertEquals("display", Processor.linkName("logic-display"));
        assertEquals("message", Processor.linkName("message"));
        assertEquals("conveyor", Processor.linkName("titanium-conveyor"));
        assertEquals("cell", Processor.linkName("memory-cell-2"));
    }

    @Test
    void tapsLinkAndUnlink(){
        Building a = host.place(TestHost.cell, 2, 0), b = host.place(TestHost.cell, 3, 0), m = host.place(TestHost.message, 4, 0);
        Processor p = host.processor(0, 0, "set n @links\nstop");
        assertFalse(p.toggleLink(a));
        assertFalse(p.toggleLink(b));
        assertFalse(p.toggleLink(m));
        assertEquals("cell1 cell2 message1", names(p));
        assertTrue(p.toggleLink(a));
        assertEquals("cell2 message1", names(p));
        assertFalse(p.toggleLink(a));
        assertEquals("cell2 message1 cell1", names(p), "the lowest free number");
        host.run(2, p);
        assertEquals(3, num(p, "n"));
    }

    @Test
    void memory(){
        Building cell = host.place(TestHost.cell, 2, 0);
        Processor p = host.processor(0, 0, """
            write 5 cell1 3
            read x cell1 3
            read y cell1 99
            getlink g 0
            getlink h 1
            sensor c cell1 @memoryCapacity
            read s "abc" 1
            read t "abc" 5
            stop
            """);
        p.toggleLink(cell);
        host.run(2, p);
        assertEquals(5, cell.memory[3]);
        assertEquals(5, num(p, "x"));
        assertEquals(0, num(p, "y"));
        assertSame(cell, var(p, "g"));
        assertNull(var(p, "h"));
        assertEquals(64, num(p, "c"));
        assertEquals('b', num(p, "s"));
        assertNull(var(p, "t"));
    }

    @Test
    void sensors(){
        Processor p = host.processor(0, 0, """
            sensor a "abcd" @size
            sensor b null @dead
            sensor c @this @type
            sensor d @this @copper
            sensor e @this @x
            sensor f 5 @size
            stop
            """);
        host.run(2, p);
        assertEquals(4, num(p, "a"));
        assertEquals(1, num(p, "b"));
        assertSame(TestHost.processor, var(p, "c"));
        assertEquals(42, num(p, "d"));
        assertNull(var(p, "e"), "the host doesn't sense @x");
        assertNull(var(p, "f"));
    }

    @Test
    void control(){
        Building m = host.place(TestHost.message, 2, 0);
        Processor p = host.processor(0, 0, "control enabled message1 0 0 0 0\ncontrol enabled null 0 0 0 0\nstop");
        p.toggleLink(m);
        host.run(2, p);
        assertFalse(m.enabled);
        assertEquals(1, host.calls.size(), "a null target never reaches the host");
    }

    @Test
    void lookup(){
        Processor p = host.processor(0, 0, """
            lookup item a 1
            lookup item b 99
            lookup block c 0
            lookup team d 3
            lookup team e 256
            lookup sector f 0
            set n @itemCount
            stop
            """);
        host.run(2, p);
        assertSame(TestHost.lead, var(p, "a"), "logic ids are logicids.dat's");
        assertNull(var(p, "b"));
        assertSame(TestHost.content.block("graphite-press"), var(p, "c"), "block logic id 0 is not air");
        assertSame(host.world.table.team(3), var(p, "d"));
        assertEquals("malis", ((LogicTeam)var(p, "d")).name());
        assertNull(var(p, "e"));
        assertNull(var(p, "f"), "a type without logic ids");
        assertEquals(TestHost.content.logicIds(ContentType.ITEM).size(), num(p, "n"));
        assertEquals(1, host.world.table.logicId(TestHost.lead));
        assertEquals(-1, host.world.table.logicId(TestHost.content.status("wet")));
        assertEquals(Program.NOOP, host.processor(1, 0, "lookup nope a 0").program().opcode(0), "an unknown type is an invalid statement");
    }

    @Test
    void setrate(){
        Processor p = host.processor(0, 0, "setrate 3\nset a @ipt\nsetrate 100\nset b @ipt\nstop");
        host.run(2, p);
        assertEquals(3, num(p, "a"));
        assertEquals(8, num(p, "b"), "a normal processor caps at its block's rate");
        Processor w = host.processor(1, 0, "setrate 100\nset b @ipt\nstop", true, 8, 1000);
        host.run(2, w);
        assertEquals(100, num(w, "b"));
    }

    @Test
    void linksFollowTheWorld(){
        Building cell = host.place(TestHost.cell, 2, 0);
        Processor p = host.processor(0, 0, "set a cell1\nset n @links\ngetlink g 0");
        p.toggleLink(cell);
        p.setCode("set a cell1\nset n @links\ngetlink g 0");
        host.run(2, p);
        assertSame(cell, var(p, "a"));

        host.remove(2, 0);
        host.run(1, p);
        assertNull(var(p, "a"), "an unlinked variable is a plain null");
        assertEquals(0, num(p, "n"));
        assertEquals("cell1", names(p));
        assertFalse(p.links().getFirst().valid());

        Building again = host.place(TestHost.cell, 2, 0);
        host.run(1, p);
        assertSame(again, var(p, "a"));
        assertEquals(1, num(p, "n"));

        // another block at the same tile renames the link
        host.place(TestHost.message, 2, 0);
        host.run(1, p);
        assertEquals("message1", names(p));
        assertNull(var(p, "a"));

        // a team change makes it invalid
        ((Building)host.building(2, 0)).team = 2;
        host.world.links.tileChanged(2, 0);
        host.run(1, p);
        assertFalse(p.links().getFirst().valid());
        assertNull(var(p, "g"));
    }

    @Test
    void configUpload(){
        host.place(TestHost.cell, 2, 0);
        host.place(TestHost.message, 3, 1);
        Processor p = host.processor(0, 0, "");
        byte[] config = LogicConfig.compress("set a cell1\nset b display1\nstop", java.util.List.of(
            new LogicConfig.Link("cell1", 2, 0),
            new LogicConfig.Link("cell7", 2, 0), // a duplicate of the same building
            new LogicConfig.Link("display1", 3, 1), // a name that doesn't match the block
            new LogicConfig.Link("cell2", 9, 9))); // nothing there yet
        p.configure(config);
        assertEquals("cell1 message1 cell2", names(p));
        assertEquals("set a cell1\nset b display1\nstop", p.code());
        host.run(2, p);
        assertSame(host.building(2, 0), var(p, "a"));
        assertNull(var(p, "b"));
        p.configure(new byte[]{1, 2, 3});
        assertEquals("cell1 message1 cell2", names(p), "invalid data changes nothing");
    }

    @Test
    void otherProcessorsByName(){
        Processor target = host.processor(5, 0, "set x 7\nset y 1\nwait 100");
        Processor p = host.processor(0, 0, """
            read a processor1 "x"
            write 9 processor1 "y"
            read c processor1 "@counter"
            read d processor1 "nothing"
            read e processor1 0
            stop
            """);
        p.toggleLink(host.building(5, 0));
        host.run(2, target, p);
        assertEquals(7, num(p, "a"));
        assertEquals(9, num(target, "y"));
        assertEquals(2, num(p, "c"), "the target sits on its wait");
        assertNull(var(p, "d"));
        assertNull(var(p, "e"), "the target has no links");
    }

    private static String names(Processor p){
        return String.join(" ", p.links().stream().map(Processor.Link::name).toList());
    }
}
