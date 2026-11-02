package com.xpdustry.shard.sim.blocks.distribution;

import com.xpdustry.shard.sim.building.*;

/**
 * Vanilla's {@code ItemBridge} as a block of its own: the phase conveyor, powered, moving one item every
 * {@code transportTime} ticks of efficiency to its link ({@link ItemBridgeBuild#updateTransport}). Unpowered, it keeps
 * its link but moves nothing; unlinked, it dumps.
 */
@Kind("item-bridge")
public final class ItemBridge extends ItemBridgeBuild{
}
