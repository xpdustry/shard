package com.xpdustry.shard.sim.spatial;

import com.xpdustry.shard.sim.unit.*;

/** A unit predicate (vanilla's {@code Boolf<Unit>}); in {@code nearbyCheck} and {@code any}, true stops the query. */
@FunctionalInterface
public interface UnitFilter{
    /** Accepts every unit. */
    UnitFilter ANY = unit -> true;
    /** {@code Unit::isValid}, the default of {@code Units.closestTarget}. */
    UnitFilter VALID = Unit::isValid;

    boolean test(Unit unit);
}
