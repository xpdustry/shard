package com.xpdustry.shard.sim.blocks.sandbox;

import com.xpdustry.shard.sim.building.*;

/**
 * Vanilla's {@code LegacyUnitFactory.LegacyUnitFactoryBuild} (LegacyUnitFactory.java), which old maps' unit factories
 * load as. A map load replaces it with its {@code replacement} (air, or the air or ground factory) at the tile's team
 * and its rotation ({@link Buildings#endLoad}); one placed live does nothing.
 */
@Kind("legacy-unit-factory")
public final class LegacyUnitFactory extends Building{
}
