package com.xpdustry.shard.sim.unit;

/**
 * How a unit type moves, which picks its {@link MoveShape}. Each value stands for the movement components of vanilla's
 * generated entity classes ({@code EntityMapping}); the other components (payload, tether, timed kill) are separate
 * parts of {@link Unit}.
 */
public enum Locomotion{
    /** {@code MechUnit}: {@code ElevationMoveComp} + {@code MechComp} (dagger, nova…). */
    MECH,
    /** {@code LegsUnit}: {@code LegsComp} (atrax, spiroct, corvus…). */
    LEGS,
    /** {@code TankUnit}: {@code ElevationMoveComp} + {@code TankComp} (stell…). */
    TANK,
    /** {@code CrawlUnit}: {@code CrawlComp} (latum, renale). */
    CRAWL,
    /** {@code UnitWaterMove}: {@code WaterMoveComp} (risso, retusa…). */
    NAVAL,
    /** {@code ElevationMoveUnit}: {@code ElevationMoveComp} alone (elude). */
    HOVER,
    /**
     * {@code UnitEntity} and the other classes without a movement component (flare, mono, mega, missiles, the block
     * unit): no tile collisions at all.
     */
    FLYING
}
