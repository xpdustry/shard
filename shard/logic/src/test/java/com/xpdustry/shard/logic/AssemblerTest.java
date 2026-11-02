package com.xpdustry.shard.logic;

import org.junit.jupiter.api.*;

import static com.xpdustry.shard.logic.TestHost.*;
import static org.junit.jupiter.api.Assertions.*;

/** Parsing: literals, labels, comments, renamed tokens, invalid code and the statements a processor may not run. */
class AssemblerTest{
    final TestHost host = new TestHost();

    private Processor run(String code){
        Processor p = host.processor(0, 0, code);
        host.run(2, p); // the first tick only fills the accumulator
        return p;
    }

    @Test
    void numberLiterals(){
        Processor p = run("""
            set a 0x1F
            set b 0b101
            set c 1e3
            set d .5
            set e -0x10
            set f 2E-3
            stop
            """);
        assertEquals(31, num(p, "a"));
        assertEquals(5, num(p, "b"));
        assertEquals(1000, num(p, "c"));
        assertEquals(0.5, num(p, "d"));
        assertEquals(-16, num(p, "e"));
        assertEquals(0.002, num(p, "f"), 1e-12);
    }

    @Test
    void colorsAndGlobals(){
        Processor p = run("""
            set a %ff000080
            set b %[red]
            set c @colorRed
            set d @pi
            set e true
            stop
            """);
        assertEquals(MlogMath.packColor(1f, 0f, 0f, 128 / 255f), num(p, "a"));
        assertEquals(LogicColors.get("red"), num(p, "b"));
        assertEquals(LogicColors.get("red"), num(p, "c"));
        assertEquals((double)3.1415927f, num(p, "d"));
        assertEquals(1, num(p, "e"));
    }

    @Test
    void labelsAndComments(){
        Processor p = run("""
            # a comment
            set i 0 # trailing
            loop:
            op add i i 1
            jump loop lessThan i 3
            stop
            """);
        assertEquals(3, num(p, "i"));
        assertEquals(4, p.program().length);
    }

    @Test
    void renamedTokens(){
        Processor p = run("""
            op atan2 a 1 1
            op dst b 3 4
            stop
            """);
        assertEquals(45, num(p, "a"), 1e-4);
        assertEquals(5, num(p, "b"), 1e-6);
    }

    @Test
    void unknownStatementsAreNoops(){
        Processor p = run("""
            bogus a b
            set x 1
            stop
            """);
        assertEquals(3, p.program().length);
        assertEquals(1, num(p, "x"));
    }

    @Test
    void privilegedStatementsAreNoopsInNormalProcessors(){
        Processor p = run("""
            setrule waveTimer true 0 0 100 100
            set x 1
            stop
            """);
        assertEquals(3, p.program().length);
        assertEquals(Program.NOOP, p.program().opcode(0));
        assertTrue(host.calls.isEmpty());
    }

    @Test
    void clientDataIsDroppedWithoutTheRule(){
        Processor p = host.processor(0, 0, "clientdata \"c\" 1 0\nset x 1\nstop", true, 8, 1000);
        assertEquals(2, p.program().length);
        host.world.allowLogicData(true);
        p.setCode("clientdata \"c\" 1 0\nset x 1\nstop");
        assertEquals(3, p.program().length);
    }

    @Test
    void malformedCodeIsEmpty(){
        assertEquals(0, run("jump nowhere always").program().length);
        assertEquals(0, run("a b c d e f g h i j k l m n o p q").program().length);
        assertEquals(0, run("x:\nx:\nend").program().length);
    }

    @Test
    void variableOrderIsVanillas(){
        Processor p = run("set b 1\nset a 2\nop add c a b\nstop");
        Program prog = p.program();
        assertEquals("@counter", prog.variableName(0));
        assertEquals("b", prog.variableName(1));
        assertEquals("a", prog.variableName(2));
        assertEquals("c", prog.variableName(3));
        assertEquals(4, prog.variables());
    }

    @Test
    void programsAreShared(){
        Processor a = host.processor(0, 0, "set x 1");
        Processor b = host.processor(1, 0, "set x 1");
        Processor c = host.processor(2, 0, "set x 1", true, 8, 1000);
        assertSame(a.program(), b.program());
        assertNotSame(a.program(), c.program());
        assertTrue(host.world.table.cache().hits() > 0);
    }
}
