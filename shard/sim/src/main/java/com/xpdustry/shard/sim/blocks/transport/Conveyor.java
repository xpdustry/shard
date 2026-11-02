package com.xpdustry.shard.sim.blocks.transport;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;

/**
 * Vanilla's {@code Conveyor} (Conveyor.java): the basic and titanium conveyors, which take items from behind and from
 * either side. The belt itself is {@link ConveyorBuild}.
 */
@Kind("conveyor")
public final class Conveyor extends ConveyorBuild{
    /** Conveyor.java:82: from a block that outputs items, or one this points at that has items, if either faces the other. */
    @Override
    boolean blends(int otherx, int othery, int otherrot, BlockDef otherblock){
        return (Blending.outputsItems(otherblock) || (Blending.lookingAt(this, otherx, othery, otherblock) && otherblock.hasItems()))
            && Blending.lookingAtEither(this, otherx, othery, otherrot, otherblock);
    }
}
