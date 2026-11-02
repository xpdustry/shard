package com.xpdustry.shard.sim.spatial;

import com.xpdustry.shard.sim.building.*;

/** A building predicate (vanilla's {@code Boolf<Building>}). */
@FunctionalInterface
public interface BuildingFilter{
    /** Accepts every building. */
    BuildingFilter ANY = building -> true;

    boolean test(Building building);
}
