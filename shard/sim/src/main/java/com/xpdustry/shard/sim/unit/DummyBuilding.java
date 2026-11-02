package com.xpdustry.shard.sim.unit;

/**
 * The building a target dummy unit belongs to ({@link Unit#tether}): vanilla's {@code TargetDummyComp.rawDamage} hands
 * it every hit instead of taking it ({@code td.dummyHit(amount)}).
 */
public interface DummyBuilding{
    /** {@code dummyHit(damage)}: counts one hit of {@code damage}, after armor. */
    void dummyHit(float damage);
}
