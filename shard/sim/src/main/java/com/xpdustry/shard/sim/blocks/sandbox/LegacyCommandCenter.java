package com.xpdustry.shard.sim.blocks.sandbox;

import com.xpdustry.shard.sim.building.*;

/**
 * Vanilla's {@code LegacyCommandCenter.LegacyCommandBuild} (LegacyCommandCenter.java), the old command center. A map
 * load removes it ({@code LegacyBlock.removeSelf}, {@link Buildings#endLoad}); one placed live does nothing.
 */
@Kind("legacy-command-center")
public final class LegacyCommandCenter extends Building{
}
