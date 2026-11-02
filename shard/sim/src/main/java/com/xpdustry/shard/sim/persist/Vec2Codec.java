package com.xpdustry.shard.sim.persist;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.registry.*;

/** {@code :common}'s {@link Vec2}, owned by whatever holds it: a unit's velocity, a leg's joint. */
@Register
public final class Vec2Codec implements ClassCodec<Vec2>{
    @Override
    public Class<Vec2> type(){
        return Vec2.class;
    }

    @Override
    public void write(Vec2 value, CheckpointOut out){
        out.f("x", value.x).f("y", value.y);
    }

    @Override
    public void read(Vec2 value, CheckpointIn in){
        value.x = in.f("x");
        value.y = in.f("y");
    }
}
