package com.xpdustry.shard.logic.oracle;

import com.xpdustry.shard.logic.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The prototype's realistic programs (sensors, sorters, memory, displays, waits), 3,000 ticks each: vanilla runs
 * one copy of the region, Shard the other, and they are compared after every tick ({@link Pair#compare()}, including
 * the linked buildings). A second run compares only every 100 ticks, so the waiting processors park in between.
 */
class ProgramsOracleTest{
    static final int ticks = 3_000;

    @ParameterizedTest
    @ValueSource(strings = {"math", "arith", "sorter", "display", "wait"})
    void everyTick(String name){
        run(name, 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sorter", "wait"})
    void sparse(String name){
        long parked = run(name, 100);
        if(name.equals("wait")) assertTrue(parked > ticks / 2, "the wait loop should park most ticks, parked " + parked);
    }

    private static long run(String name, int every){
        Scenarios.Scenario scenario = Scenarios.create(name, 2);
        VanillaHost host = new VanillaHost();
        LogicWorld world = VanillaLogic.logicWorld(host);
        Pair pair = new Pair(scenario.processors().get(0), scenario.processors().get(1), world, host);
        pair.processor = VanillaLogic.processor(world, host, pair.shard, pair.vanilla);
        pair.processor.setCode(scenario.code());
        assertNull(pair.compare(), "after loading");
        for(int t = 0; t < ticks; t++){
            pair.tick();
            if(t % every == every - 1){
                String bad = pair.compare();
                int tick = t;
                assertNull(bad, () -> name + ", tick " + tick + ": " + bad);
            }
        }
        assertTrue(world.executed() > 0);
        return world.parkedCalls();
    }
}
