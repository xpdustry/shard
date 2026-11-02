package com.xpdustry.shard.logic;

import com.xpdustry.shard.common.math.*;
import org.junit.jupiter.api.*;

import static com.xpdustry.shard.logic.TestHost.*;
import static org.junit.jupiter.api.Assertions.*;

/** {@code set}, {@code op} and {@code select}: LVar's number and object rules. */
class ArithmeticTest{
    final TestHost host = new TestHost();

    private Processor run(String code){
        Processor p = host.processor(0, 0, code + "\nstop");
        host.run(2, p); // the first tick only fills the accumulator
        return p;
    }

    @Test
    void arithmetic(){
        Processor p = run("""
            op idiv a 7 2
            op mod b -7 3
            op emod c -7 3
            op pow d 2 10
            op shl e 1 3
            op not f 0
            op max g 3 9
            op abs h -2.5
            """);
        assertEquals(3, num(p, "a"));
        assertEquals(-1, num(p, "b"));
        assertEquals(2, num(p, "c"));
        assertEquals(1024, num(p, "d"));
        assertEquals(8, num(p, "e"));
        assertEquals(-1, num(p, "f"));
        assertEquals(9, num(p, "g"));
        assertEquals(2.5, num(p, "h"));
    }

    @Test
    void invalidNumbersBecomeNull(){
        Processor p = run("""
            set a 5
            op div a 1 0
            op log b -1 0
            op sqrt c -1 0
            """);
        assertNull(var(p, "a"));
        assertNull(var(p, "b"));
        assertNull(var(p, "c"));
    }

    @Test
    void equality(){
        Processor p = run("""
            op equal a "x" "x"
            op strictEqual b 1 "1"
            op equal c null 0
            op strictEqual d null 0
            op notEqual e @copper @lead
            op equal f 0.1 0.1000001
            """);
        assertEquals(1, num(p, "a"));
        assertEquals(0, num(p, "b"));
        assertEquals(1, num(p, "c"));
        assertEquals(0, num(p, "d"));
        assertEquals(1, num(p, "e"));
        assertEquals(1, num(p, "f"));
    }

    @Test
    void constantsIgnoreWrites(){
        Processor p = run("""
            set true 5
            op add @pi 1 1
            set a true
            set b @pi
            """);
        assertEquals(1, num(p, "a"));
        assertEquals((double)3.1415927f, num(p, "b"));
    }

    @Test
    void objectsReadAsNumbers(){
        Processor p = run("""
            op add a @copper 1
            op add b null 1
            set s "text"
            op add c s 0
            """);
        assertEquals(2, num(p, "a"));
        assertEquals(1, num(p, "b"));
        assertEquals(1, num(p, "c"));
        assertEquals("text", var(p, "s"));
    }

    @Test
    void select(){
        Processor p = run("""
            select a lessThan 1 2 "yes" "no"
            select b always 0 0 @copper 3
            select c strictEqual 1 1.0 4 5
            """);
        assertEquals("yes", var(p, "a"));
        assertSame(TestHost.copper, var(p, "b"));
        assertEquals(4, num(p, "c"));
    }

    @Test
    void trigUsesDegreesAndStrictMath(){
        Processor p = run("""
            op sin a 30 0
            op cos b 60 0
            op asin c 0.5 0
            op angle d 0 1
            op len e 3 4
            op noise f 1.5 2.5
            """);
        assertEquals(StrictMath.sin(30 * Mathf.doubleDegRad), num(p, "a"));
        assertEquals(StrictMath.cos(60 * Mathf.doubleDegRad), num(p, "b"));
        assertEquals(StrictMath.asin(0.5) * Mathf.doubleRadDeg, num(p, "c"));
        assertEquals(90, num(p, "d"), 1e-4);
        assertEquals(5, num(p, "e"), 1e-6);
        assertEquals(MlogMath.noise(0, 1.5, 2.5), num(p, "f"));
    }

    @Test
    void randUsesTheWorldsGenerator(){
        host.world.rand(new Rand(42));
        Processor p = run("op rand a 10 0");
        double expected = new Rand(42).nextDouble() * 10;
        assertEquals(expected, num(p, "a"));
    }

    @Test
    void colors(){
        Processor p = run("""
            packcolor c 1 0.5 0 1
            unpackcolor r g b a c
            packcolor d 2 -1 0 0
            """);
        assertEquals(MlogMath.packColor(1f, 0.5f, 0f, 1f), num(p, "c"));
        assertEquals(1, num(p, "r"));
        assertEquals(127 / 255f, num(p, "g"), "rgba8888 truncates");
        assertEquals(0, num(p, "b"));
        assertEquals(1, num(p, "a"));
        assertEquals(MlogMath.packColor(1f, 0f, 0f, 0f), num(p, "d"));
    }
}
