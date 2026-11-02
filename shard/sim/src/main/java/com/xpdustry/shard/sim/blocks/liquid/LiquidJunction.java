package com.xpdustry.shard.sim.blocks.liquid;

import com.xpdustry.shard.sim.building.*;

/**
 * Vanilla's {@code LiquidJunction.LiquidJunctionBuild} (LiquidJunction.java:40): it holds nothing itself and never
 * updates. Liquid offered to it goes straight through to the building on the far side, as its
 * {@code getLiquidDestination}, through any junctions in a row; when the far side doesn't take that liquid, the
 * destination is the junction itself, which takes nothing (it consumes no liquid), so the flow stops there.
 * Disabled by logic, it is its own destination.
 *
 * <p>Vanilla quirk kept: a source that isn't beside it (relative direction -1) sends liquid to the junction's
 * bottom neighbour, since {@code (-1 + 4) % 4} is 3. Its block doesn't leak (vanilla's TODO).
 */
@Kind("liquid-junction")
public final class LiquidJunction extends Building{
    @Override
    public Building getLiquidDestination(Building source, int liquid){
        if(!enabled) return this;
        int dir = (source.relativeTo(tileX(), tileY()) + 4) % 4;
        Building next = nearby(dir);
        if(next == null || (!next.acceptLiquid(this, liquid) && !(next instanceof LiquidJunction))){
            return this;
        }
        return next.getLiquidDestination(this, liquid);
    }
}
