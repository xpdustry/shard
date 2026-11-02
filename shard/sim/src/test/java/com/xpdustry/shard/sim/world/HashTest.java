package com.xpdustry.shard.sim.world;

import com.xpdustry.shard.sim.toy.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/** {@link WorldHash}: stable across runs, sensitive to state, and named by section. */
class HashTest{
    /**
     * The hash of {@code Worlds.churned(42, 500)}. A change here means the simulation's results changed: make sure
     * that was intended (a ported behaviour, a fixed bug), then update the constant.
     */
    static final long GOLDEN = 0xce7fd47e003afb52L;

    @Test
    void theHashOfAScriptedWorldIsGolden(){
        long value = Worlds.churned(42, 500).hash().value();
        assertEquals(GOLDEN, value, String.format("golden hash moved to 0x%016xL", value));
    }

    @Test
    void twoRunsHashAlikeEveryTick(){
        World a = Worlds.quiet(7), b = Worlds.quiet(7);
        a.part(Chaos.class).enabled = b.part(Chaos.class).enabled = true;
        for(int i = 0; i < 300; i++){
            a.tick();
            b.tick();
            assertEquals(a.hash(), b.hash(), "tick " + i);
        }
        World c = Worlds.churned(8, 300);
        assertNotEquals(a.hash().value(), c.hash().value(), "different seeds should diverge");
    }

    @Test
    void sectionsNameWhatDiverged(){
        World a = Worlds.churned(9, 100);
        World b = a.fork();
        b.bullets().x[0] = Math.nextUp(b.bullets().x[0]);
        assertEquals("bullets", a.hash().firstDifference(b.hash()).orElseThrow());
        World c = a.fork();
        c.rand().nextInt();
        assertEquals("clock", a.hash().firstDifference(c.hash()).orElseThrow());
        World d = a.fork();
        d.part(Chaos.class).fired++;
        assertEquals(Chaos.class.getName(), a.hash().firstDifference(d.hash()).orElseThrow());
        assertTrue(a.hash().toString().contains("tiles="));
    }

    @Test
    void negativeZeroIsADifferentState(){
        World a = Worlds.churned(10, 50);
        World b = a.fork();
        a.bullets().damage[0] = 0f;
        b.bullets().damage[0] = -0f;
        assertNotEquals(a.hash(), b.hash());
    }
}
