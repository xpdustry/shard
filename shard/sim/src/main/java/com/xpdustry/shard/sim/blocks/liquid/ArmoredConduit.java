package com.xpdustry.shard.sim.blocks.liquid;

import com.xpdustry.shard.sim.building.*;

/**
 * Vanilla's {@code ArmoredConduit.ArmoredConduitBuild} (ArmoredConduit.java:22): the plated and reinforced conduits.
 * A conduit's rules ({@link ConduitBuild}), and from the side it takes only from conduits, direction liquid bridges and
 * liquid junctions, or from a building that faces it or isn't its neighbour (a building reaching it through a
 * junction). Their blocks don't leak.
 */
@Kind("armored-conduit")
public final class ArmoredConduit extends ConduitBuild{
    @Override
    public boolean acceptLiquid(Building source, int liquid){
        // vanilla's "super hacky" proximity check, for a block feeding a conduit through a junction
        return super.acceptLiquid(source, liquid) && (source instanceof ConduitBuild || source instanceof DirectionLiquidBridge
            || source instanceof LiquidJunction || source.relativeTo(tileX(), tileY()) == rotation || !neighbours(source));
    }

    /** {@code source.proximity.contains(this)}. */
    private boolean neighbours(Building source){
        for(int i = 0; i < source.proximitySize(); i++){
            if(source.proximity(i) == this) return true;
        }
        return false;
    }
}
