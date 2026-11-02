package com.xpdustry.shard.sim.blocks.defense;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.bullet.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.spatial.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code BaseShield.BaseShieldBuild} (BaseShield.java), the editor's shield projectors: a round shield of
 * {@code radius}, grown with efficiency, that absorbs every enemy bullet inside it and pushes enemy units out, killing
 * those stuck deep inside. It has no health: {@link #broken} is saved but nothing sets it.
 *
 * <p>Bullets come from the bullet group's tree in vanilla's order ({@link Bullets#intersectQuad}); unlike the force
 * projector it doesn't skip absorbed ones, so a bullet removed earlier this tick is absorbed again, which only marks
 * it. A pushed unit's spark effect draws its chance.
 */
@Kind("base-shield")
public final class BaseShield extends Building implements Checkpointable{
    public boolean broken;
    public float hit, smoothRadius;

    @Derived("the block's radius, read on first use")
    private float radius = Float.NaN;

    float shieldRadius(){
        if(Float.isNaN(radius)) radius = block().baked() ? block().fields().f("radius") : 200f;
        return radius;
    }

    @Override
    public void updateTile(){
        smoothRadius = Mathf.lerpDelta(smoothRadius, shieldRadius() * efficiency, 0.05f);
        float rad = radius();
        if(rad > 1){
            World world = world();
            Bullets bullets = world.bullets();
            BulletTypes types = bullets.types(world);
            float x = x(), y = y();
            var absorbed = new ArrayList<Bullet>();
            bullets.intersectQuad(x - rad, y - rad, rad * 2f, rad * 2f, (table, row) -> {
                if(table.team[row] != team && types.get(table.type[row]).absorbable && Mathf.within(table.x[row], table.y[row], x, y, rad)){
                    absorbed.add(Bullet.ofObject(world, table.object[row]));
                }
            });
            for(Bullet bullet : absorbed) bullet.absorb();
            SpatialIndex.of(world).nearbyEnemies(team, x, y, rad + 10f, unit -> push(unit, x, y, rad));
        }
    }

    /** {@code unitConsumer}: pushes a unit overlapping the shield out of it, or kills it when it is stuck inside. */
    private void push(Unit unit, float x, float y, float rad){
        // if this is positive, repel the unit; if it exceeds the unit radius * 2, it's inside the forcefield and must be killed
        float overlapDst = (unit.hitSize / 2f + rad) - unit.dst(x, y);
        if(overlapDst > 0){
            if(overlapDst > unit.hitSize * 1.5f){
                unit.kill();
            }else{
                unit.vel.setZero();
                var away = new Vec2().set(unit.x, unit.y).sub(x, y).setLength(overlapDst + 0.01f);
                unit.move(away.x, away.y);
                // Fx.circleColorSpark's chance: Mathf.chanceDelta(0.12f * Time.delta)
                Mathf.chanceDelta(world().rand(), 0.12f);
            }
        }
    }

    /** {@code radius()}: the shield's current radius. */
    public float radius(){
        return smoothRadius;
    }

    @Override
    public void write(CheckpointOut out){
        out.bool("broken", broken).f("hit", hit).f("smoothRadius", smoothRadius);
    }

    @Override
    public void read(CheckpointIn in){
        broken = in.bool("broken");
        hit = in.f("hit");
        smoothRadius = in.f("smoothRadius");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (BaseShield)into;
        out.broken = broken;
        out.hit = hit;
        out.smoothRadius = smoothRadius;
        out.radius = radius;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.bool(broken).f(hit).f(smoothRadius);
    }
}
