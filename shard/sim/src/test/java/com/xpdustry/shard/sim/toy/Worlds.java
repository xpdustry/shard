package com.xpdustry.shard.sim.toy;

import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.world.*;

/** Worlds for the tests. */
public final class Worlds{
    /**
     * A 48×48 world of toy content with the built-in systems (which include the toy ones on the test class path). The
     * map has no cores, so the unit cap is off: under vanilla's default rules every unit would die on spawn.
     */
    public static World quiet(long seed){
        World world = new World(WorldConfig.of(ToyContent.INSTANCE, 48, 48, seed));
        world.part(WorldRules.class).rules.disableUnitCap = true;
        return world;
    }

    /** A world where {@link Chaos} has churned for {@code ticks} ticks. */
    public static World churned(long seed, int ticks){
        World world = quiet(seed);
        world.part(Chaos.class).enabled = true;
        for(int i = 0; i < ticks; i++) world.tick();
        return world;
    }

    private Worlds(){}
}
