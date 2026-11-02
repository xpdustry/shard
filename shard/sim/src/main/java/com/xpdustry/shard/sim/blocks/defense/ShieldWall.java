package com.xpdustry.shard.sim.blocks.defense;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.bullet.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code ShieldWall.ShieldWallBuild} (ShieldWall.java): a powered wall whose shield takes damage first. A
 * broken shield (emptied, or unpowered) takes nothing until {@code breakCooldown} has passed; otherwise it regenerates
 * by {@code regenSpeed} a tick, scaled by efficiency. It takes hits as {@link Wall} does ({@link WallHits}).
 *
 */
@Kind("shield-wall")
public final class ShieldWall extends Building implements BulletCollider, Checkpointable{
    public float hit;
    public float shield, shieldRadius;
    public float breakTimer;
    @Derived("the block's wall parameters, read on first use")
    private WallHits hits;
    @Derived("the block's shieldHealth, breakCooldown and regenSpeed, read on first use")
    private float[] params;

    @Override
    public void created(){
        shield = params()[0];
    }

    @Override
    public void updateTile(){
        float[] p = params();
        if(breakTimer > 0){
            breakTimer -= 1f;
        }else{
            // regen when not broken
            shield = Mathf.clamp(shield + p[2] * edelta(), 0f, p[0]);
        }
        if(hit > 0){
            hit -= 1f / 10f;
            hit = Math.max(hit, 0f);
        }
        shieldRadius = Mathf.lerpDelta(shieldRadius, broken() ? 0f : 1f, 0.12f);
    }

    public boolean broken(){
        return breakTimer > 0 || !canConsume();
    }

    @Override
    public void damage(float damage){
        float shieldTaken = broken() ? 0f : Math.min(shield, damage);
        shield -= shieldTaken;
        if(shieldTaken > 0) hit = 1f;
        // shield was destroyed, needs to go down
        if(shield <= 0.00001f && shieldTaken > 0) breakTimer = params()[1];
        if(damage - shieldTaken > 0) super.damage(damage - shieldTaken);
    }

    @Override
    public boolean collision(Bullet bullet){
        if(hits == null) hits = WallHits.of(block());
        boolean remove = hits.collision(this, bullet);
        hit = 1f;
        return remove;
    }

    /** {@code shieldHealth}, {@code breakCooldown}, {@code regenSpeed}. */
    private float[] params(){
        if(params == null){
            var fields = block().fields();
            params = block().baked()
                ? new float[]{fields.f("shieldHealth"), fields.f("breakCooldown"), fields.f("regenSpeed")}
                : new float[]{900f, 60f * 10f, 2f};
        }
        return params;
    }

    @Override
    public void write(CheckpointOut out){
        out.f("hit", hit).f("shield", shield).f("shieldRadius", shieldRadius).f("breakTimer", breakTimer);
    }

    @Override
    public void read(CheckpointIn in){
        hit = in.f("hit");
        shield = in.f("shield");
        shieldRadius = in.f("shieldRadius");
        breakTimer = in.f("breakTimer");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (ShieldWall)into;
        out.hit = hit;
        out.shield = shield;
        out.shieldRadius = shieldRadius;
        out.breakTimer = breakTimer;
        out.hits = hits;
        out.params = params;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.f(hit).f(shield).f(shieldRadius).f(breakTimer);
    }

    /** {@code pickedUp()}: a payload unit lifts it, and its shield's radius drops. */
    @Override
    public void pickedUp(){
        shieldRadius = 0f;
    }
}
