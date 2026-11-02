package com.xpdustry.shard.sim.combat;

import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Combat's remote calls, published where vanilla sends them. A server encodes each as the packet of the same name.
 */
public sealed interface CombatEvent extends WorldEvent{
    /**
     * {@code Units.notifyUnitSpawn(unit)}: {@code Call.unitSpawn}, so a unit a bullet made (a missile, a despawn unit)
     * appears before the next snapshot. Published after the unit is added.
     */
    record UnitSpawned(int unit) implements CombatEvent{
    }

    /** Publishes {@link UnitSpawned} for {@code unit}. */
    static void unitSpawned(Unit unit){
        unit.world().publish(new UnitSpawned(unit.id()));
    }
}
