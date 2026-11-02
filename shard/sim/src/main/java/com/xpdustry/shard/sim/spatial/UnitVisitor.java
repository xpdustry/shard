package com.xpdustry.shard.sim.spatial;

import com.xpdustry.shard.sim.unit.*;

/** What a unit query calls for each unit it finds (vanilla's {@code Cons<Unit>}). */
@FunctionalInterface
public interface UnitVisitor{
    void visit(Unit unit);
}
