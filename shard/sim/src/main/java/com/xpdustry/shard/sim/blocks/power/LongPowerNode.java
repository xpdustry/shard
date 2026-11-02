package com.xpdustry.shard.sim.blocks.power;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code LongPowerNode.LongPowerNodeBuild}: the beam link, a {@link LaserNode} that doesn't autolink. Its
 * {@link #warmup} only lights its glow, and vanilla quirk kept: the block doesn't update, so it stays 0 on a server.
 */
@Kind("long-power-node")
public final class LongPowerNode extends LaserNode implements Checkpointable{
    public float warmup;

    @Override
    public void updateTile(){
        warmup = Mathf.lerpDelta(warmup, power.links.size > 0 ? 1f : 0f, 0.05f);
    }

    @Override
    public void write(CheckpointOut out){
        out.f("warmup", warmup);
    }

    @Override
    public void read(CheckpointIn in){
        warmup = in.f("warmup");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        ((LongPowerNode)into).warmup = warmup;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.f(warmup);
    }
}
