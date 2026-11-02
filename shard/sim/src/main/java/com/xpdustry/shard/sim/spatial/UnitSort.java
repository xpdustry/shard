package com.xpdustry.shard.sim.spatial;

import com.xpdustry.shard.sim.unit.*;

/** Vanilla's {@code Units.Sortf}: a target's cost from a position, lowest wins. The vanilla sorts are {@code UnitSorts}. */
@FunctionalInterface
public interface UnitSort{
    /** {@code UnitSorts.closest}: {@code Unit::dst2}. */
    UnitSort CLOSEST = Unit::dst2;

    float cost(Unit unit, float x, float y);
}
