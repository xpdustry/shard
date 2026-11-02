package com.xpdustry.shard.sim.blocks.sandbox;

import com.xpdustry.shard.sim.building.*;

/**
 * Vanilla's {@code Building} itself, for blocks whose class declares no building of its own (build kind {@code block}).
 * Off the environment, whose blocks have no building, only the power void is one: everything it does is its
 * {@code consumePower(Float.MAX_VALUE)}, which the base runs, so it drains its graph's whole supply. A
 * {@code LegacyBlock} without a building class of its own would be one too; vanilla has none.
 */
@Kind("block")
public final class PlainBuilding extends Building{
}
