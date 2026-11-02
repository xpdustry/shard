package com.xpdustry.shard.sim.building;

/**
 * Vanilla's {@code BlockStatus}, in its order: what {@link Building#status()} reports, which mlog's
 * {@code sensor @status} and the random-wave AI read. The colours are the client's business.
 */
public enum BlockStatus{
    active, noOutput, noInput, logicDisable, inactiveUnitFactory, inactive
}
