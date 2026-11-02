package com.xpdustry.shard.sim.blocks.sandbox;

import com.xpdustry.shard.sim.building.*;

/**
 * Vanilla's {@code LegacyMechPad.LegacyMechPadBuild} (LegacyMechPad.java), which old maps' mech and ship pads load as.
 * A map load removes it ({@code LegacyBlock.removeSelf}, {@link Buildings#endLoad}); one placed live does nothing.
 */
@Kind("legacy-mech-pad")
public final class LegacyMechPad extends Building{
}
