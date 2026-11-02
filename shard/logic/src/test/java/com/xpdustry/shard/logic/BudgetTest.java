package com.xpdustry.shard.logic;

import org.junit.jupiter.api.*;

import java.util.*;

import static com.xpdustry.shard.logic.TestHost.*;
import static org.junit.jupiter.api.Assertions.*;

/** The throttle: off by default, deterministic when on, per team, and world processors exempt. */
class BudgetTest{
    static final String busy = "op add n n 1\nsensor s @this @enabled\njump 0 always";

    @Test
    void offByDefault(){
        TestHost host = new TestHost();
        Processor p = host.processor(0, 0, busy, false, 50, 50);
        host.run(100, p);
        assertEquals(1f, host.world.budget.factor(1));
        assertEquals(99 * 50 / 3 + 1, num(p, "n"), 1);
    }

    @Test
    void throttlesDeterministically(){
        List<String> first = throttled(), second = throttled();
        assertFalse(first.isEmpty());
        assertEquals(first, second);
    }

    private static List<String> throttled(){
        TestHost host = new TestHost();
        List<String> changes = new ArrayList<>();
        host.world.budget.listener((tick, team, before, after, used) -> changes.add(tick + ":" + team + ":" + after));
        host.world.budget.configure(new LogicBudget.Config(30, 0.3, 0.02, 0.05, false, Map.of()));
        Processor p = host.processor(0, 0, busy, false, 50, 50);
        Processor other = host.processor(1, 0, busy, false, 50, 50);
        ((Building)other.building()).team = 2;
        other.team(2);
        Processor world = host.processor(2, 0, busy, true, 50, 1000);
        host.run(300, p, other, world);
        float f = host.world.budget.factor(1);
        assertTrue(f < 1f && f > 0, "factor " + f);
        assertEquals(f, host.world.budget.factor(2), "the same load gives the same factor per team");
        assertTrue(host.world.budget.used(1) == 0, "counting restarts every tick");
        assertTrue(num(world, "n") > num(p, "n") * 1.5, "world processors are exempt");
        return changes;
    }
}
