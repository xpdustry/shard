package com.xpdustry.shard.compat.v160.entity;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.sim.puddle.*;
import com.xpdustry.shard.wire.v160_7.*;

/**
 * One puddle, frozen for {@link PuddleLayout}: its id, then {@code Puddle}'s synced fields, the liquid as its content
 * id (-1 for none) and the tile as {@code TypeIO.writeTile} writes it.
 */
public final class PuddleProjection implements PuddleLayout.Source{
    private final int id, tile;
    private final short liquid;
    private final float amount, x, y;

    PuddleProjection(Puddle puddle){
        id = puddle.id();
        tile = puddle.tile < 0 ? Point2.pack(-1, -1) : puddle.world().tiles().toWire(puddle.tile);
        liquid = (short)puddle.liquid;
        amount = puddle.amount;
        x = puddle.x;
        y = puddle.y;
    }

    /** The puddle's entity id. */
    public int id(){
        return id;
    }

    @Override
    public float amount(){
        return amount;
    }

    @Override
    public short liquid(){
        return liquid;
    }

    @Override
    public int tile(){
        return tile;
    }

    @Override
    public float x(){
        return x;
    }

    @Override
    public float y(){
        return y;
    }
}
