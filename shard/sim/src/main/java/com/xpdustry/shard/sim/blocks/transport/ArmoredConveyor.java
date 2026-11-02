package com.xpdustry.shard.sim.blocks.transport;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;

/**
 * Vanilla's {@code ArmoredConveyor} (ArmoredConveyor.java): a {@link ConveyorBuild} that takes items from the sides
 * only from conveyors, and from behind from anything.
 */
@Kind("armored-conveyor")
public final class ArmoredConveyor extends ConveyorBuild{
    private static final KindKey CONVEYOR = KindKey.of("conveyor");

    @Override
    public boolean acceptItem(Building source, int item){
        return super.acceptItem(source, item) && (isConveyor(source.block())
            || Blending.relativeTo(world(), Blending.facingEdge(source, this), tileX(), tileY()) == rotation);
    }

    /** ArmoredConveyor.java:16, with its {@code blendsArmored}, which takes rotated outputs from conveyors only. */
    @Override
    boolean blends(int otherx, int othery, int otherrot, BlockDef otherblock){
        return (Blending.outputsItems(otherblock) && Blending.blendsArmored(this, otherx, othery, otherrot, otherblock, ArmoredConveyor::isConveyor))
            || (Blending.lookingAt(this, otherx, othery, otherblock) && otherblock.hasItems());
    }

    /** {@code block instanceof Conveyor}: the conveyors and armored conveyors. */
    static boolean isConveyor(BlockDef block){
        return block.baked() && block.isA(CONVEYOR);
    }
}
