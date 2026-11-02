package com.xpdustry.shard.sim.rules;

/** Vanilla's {@code Env} flags, which {@link Rules#env} combines. */
public final class Env{
    public static final int
        NONE = 0,
        TERRESTRIAL = 1,
        SPACE = 1 << 1,
        UNDERWATER = 1 << 2,
        SPORES = 1 << 3,
        SCORCHING = 1 << 4,
        GROUND_OIL = 1 << 5,
        GROUND_WATER = 1 << 6,
        OXYGEN = 1 << 7,
        ANY = 0xffffffff;

    /** Vanilla's {@code Vars.defaultEnv}: Serpulo's environment, 233. */
    public static final int DEFAULT = TERRESTRIAL | SPORES | GROUND_OIL | GROUND_WATER | OXYGEN;

    private Env(){}
}
