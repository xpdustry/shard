package com.xpdustry.shard.sim.blocks.defense;

import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.bullet.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code Wall.WallBuild} (Wall.java): every wall. A bullet that hits one does its damage, then a surge wall
 * may arc lightning back along the bullet's path and a phase wall may deflect it, flipping the bullet to the wall's
 * team ({@link WallHits}). What else walls do is the block's data, which the base and the bullets read already:
 * plastanium's {@code absorbLasers} (lasers stop at it) and {@code insulated} (power lasers, beams and lightning don't
 * cross it), armor, and {@code flashHit}, which only draws.
 *
 * <p>{@link #hit} is set on every hit and never decays on a server: vanilla fades it in {@code draw()}.
 *
 * <p>Not ported: {@code autotile}, which only draws (no vanilla wall sets it).
 */
@Kind("wall")
public final class Wall extends Building implements BulletCollider, Checkpointable{
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
        var out = (Wall)into;
        out.hit = hit;
        out.hits = hits;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.f(hit);
    }
}
