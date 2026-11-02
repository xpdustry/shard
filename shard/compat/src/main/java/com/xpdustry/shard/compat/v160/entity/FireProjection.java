package com.xpdustry.shard.compat.v160.entity;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.sim.fire.*;
import com.xpdustry.shard.wire.v160_7.*;

/**
 * One fire, frozen for {@link FireLayout}: its id, then {@code Fire}'s synced fields. The tile is written as
 * {@code TypeIO.writeTile} does: {@code Point2.pack(x, y)}, or {@code Point2.pack(-1, -1)} for none.
 */
public final class FireProjection implements FireLayout.Source{
    private final int id, tile;
    private final float lifetime, time, x, y;

    FireProjection(Fire fire){
        id = fire.id();
        tile = fire.tile < 0 ? Point2.pack(-1, -1) : fire.world().tiles().toWire(fire.tile);
        lifetime = fire.lifetime;
        time = fire.time;
        x = fire.x;
        y = fire.y;
    }

    /** The fire's entity id. */
    public int id(){
        return id;
    }

    @Override
    public float lifetime(){
        return lifetime;
    }

    @Override
    public int tile(){
        return tile;
    }

    @Override
    public float time(){
        return time;
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
