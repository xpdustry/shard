package com.xpdustry.shard.testkit;

import com.xpdustry.shard.testkit.toy.*;

/** Scenarios and runners over the toy kinds. */
final class Toys{
    /**
     * Two pumps, a counter configured with copper and step 2, a 2×2 wall and a flare. Lead goes into the counter at
     * tick 50, and the second pump is removed at tick 100. Entity ids: pumps 0 and 1, counter 2, wall 3, flare 4.
     */
    static Scenario pumps(long ticks){
        return Scenario.named("toy-pumps").world(12, 8).seed(7)
            .place("pump", 2, 2)
            .place("pump", 5, 2)
            .place("counter", 3, 5, 0, Config.item("copper"))
            .configure(3, 5, Config.of(2))
            .place("big-wall", 8, 5)
            // a team without a core has a unit cap of 0, and units over the cap die on add (UnitComp.java:605)
            .rule("unitCap", 16)
            .spawn("flare", 40f, 44f, 90f)
            .step(50).items(3, 5, "lead", 7)
            .step(50).remove(5, 2)
            .step(ticks - 100)
            .build();
    }

    static ShardRunner shard(){
        return ShardRunner.of(ToyContent.normal());
    }

    /** Pumps are {@link BrokenPump}s: one extra unit of water on their 100th stroke. */
    static ShardRunner broken(){
        return ShardRunner.of(ToyContent.broken()).name("shard (broken)");
    }

    /** A runner that claims to be global, so {@link Differential} records its reference side as it does for vanilla. */
    static Runner global(Runner runner){
        return new Runner(){
            @Override
            public String name(){
                return runner.name();
            }

            @Override
            public boolean global(){
                return true;
            }

            @Override
            public Session open(Scenario scenario){
                return runner.open(scenario);
            }
        };
    }

    private Toys(){}
}
