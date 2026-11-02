package com.xpdustry.shard.sim.blocks.crafting;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.sim.building.*;

/**
 * Vanilla's {@code ItemIncinerator.ItemIncineratorBuild} (ItemIncinerator.java:32), the slag incinerator: while its
 * consumers allow (slag) it takes any item and destroys it, drawing its effect's chance ({@code effectChance}) from the
 * world's random for each.
 */
@Kind("item-incinerator")
public final class ItemIncinerator extends Building{
    @Override
    public BlockStatus status(double stateTick){
        return !enabled ? BlockStatus.logicDisable : efficiency > 0 ? BlockStatus.active : BlockStatus.noInput;
    }

    @Override
    public void handleItem(Building source, int item){
        Mathf.chance(world().rand(), block().baked() ? block().fields().f("effectChance") : 0.2f);
    }

    @Override
    public boolean acceptItem(Building source, int item){
        return efficiency > 0;
    }
}
