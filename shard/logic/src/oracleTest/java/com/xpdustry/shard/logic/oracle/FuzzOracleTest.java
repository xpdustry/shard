package com.xpdustry.shard.logic.oracle;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Random programs on vanilla's {@code LExecutor} and on Shard's VM, compared tick by tick: every exported variable
 * bit for bit and in order (the {@code syncVariable} ids), the raw {@code @counter}, the accumulator, the text buffer,
 * the wait timers, {@code op rand}'s state, the links and the linked cell and message.
 */
class FuzzOracleTest{
    @Test
    void twoThousandPrograms(){
        run(2_000, 160_7L);
    }

    @Test
    @Tag("slow")
    void twentyThousandPrograms(){
        run(20_000, 28L);
    }

    private static void run(int programs, long seed){
        MlogFuzz fuzz = new MlogFuzz(seed);
        fuzz.run(programs);
        long parked = fuzz.logic.world.parkedCalls();
        System.out.printf("fuzz: %d programs, %d ticks, %d instructions, %d parked calls, %d mismatches%n", programs, fuzz.ticks,
        fuzz.logic.world.executed(), parked, fuzz.failures);
        assertTrue(parked > 0, "no processor parked");
        assertEquals(0, fuzz.failures, () -> fuzz.failures + " of " + programs + " programs differ; first:\n" + String.join("\n\n", fuzz.reports));
    }
}
