package com.xpdustry.shard.sim.unit;

/**
 * What a building does to a unit on its tiles, from {@code UnitComp.update}: {@code build.unitOnAny(unit)} for any
 * unit over it, then {@code build.unitOn(unit)} for a grounded, non-hovering one (conveyors carry units, some floors'
 * blocks hurt them). A building kind implements this to take part; others are passed over, as vanilla's empty
 * defaults are.
 */
public interface UnitContact{
    /** {@code BuildingComp.unitOnAny}: any unit whose tile is this building's. */
    default void unitOnAny(Unit unit){
    }

    /** {@code BuildingComp.unitOn}: a grounded unit standing on this building. */
    default void unitOn(Unit unit){
    }
}
