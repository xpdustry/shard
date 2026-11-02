package com.xpdustry.shard.sim.blocks.defense;

import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.bullet.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code Thruster.ThrusterBuild} (Thruster.java): a 4x4 rotating wall, sandbox only. Its building is a
 * {@code WallBuild} that only draws differently, so it takes hits as {@link Wall} does ({@link WallHits}).
 */
@Kind("thruster")
public final class Thruster extends Building implements BulletCollider, Checkpointable{
    public float hit;
    @Derived("the block's wall parameters, read on first use")
    private WallHits hits;

    @Override
    public boolean collision(Bullet bullet){
        if(hits == null) hits = WallHits.of(block());
        boolean remove = hits.collision(this, bullet);
        hit = 1f;
        return remove;
    }

    @Override
    public void write(CheckpointOut out){
        out.f("hit", hit);
    }

    @Override
    public void read(CheckpointIn in){
        hit = in.f("hit");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (Thruster)into;
        out.hit = hit;
        out.hits = hits;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.f(hit);
    }
}
