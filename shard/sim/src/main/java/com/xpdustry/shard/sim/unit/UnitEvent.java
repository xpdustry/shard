package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.sim.world.*;

/**
 * A unit's death or removal, one record per vanilla {@code Units} remote call. Each is published where vanilla's
 * {@code Call} sends its packet: after the local handler ran, so a call made inside another's handler is published
 * first, as its packet is sent first. A server encodes each as the packet of the same name.
 *
 * <p>The unit is named by entity id: a destroy may be published for a unit that is already gone, as vanilla sends
 * {@code unitDestroy} whatever its handler found.
 */
public sealed interface UnitEvent extends WorldEvent{
    /** The unit's entity id. */
    int unit();

    /** {@code Call.unitDeath(id)}: {@link Unit#kill()} on a killable unit. The unit ran {@code killed()}. */
    record Death(int unit) implements UnitEvent{
    }

    /** {@code Call.unitDestroy(id)}: a wreck reached the ground, or a posted cap or environment death ran. */
    record Destroy(int unit) implements UnitEvent{
    }

    /** {@code Call.unitDespawn(unit)}: removed without dying (core units left behind, tethers lost). */
    record Despawn(int unit) implements UnitEvent{
    }

    /** {@code Call.unitEnvDeath(unit)}: the rules' environment doesn't support the type; destroyed next tick. */
    record EnvDeath(int unit) implements UnitEvent{
    }

    /** {@code Call.unitCapDeath(unit)}: added over its team's cap; destroyed next tick. */
    record CapDeath(int unit) implements UnitEvent{
    }

    /** {@code Call.unitSafeDeath(unit)}: removed with death effects but no explosion ({@code ShieldArcAbility}). */
    record SafeDeath(int unit) implements UnitEvent{
    }
}
