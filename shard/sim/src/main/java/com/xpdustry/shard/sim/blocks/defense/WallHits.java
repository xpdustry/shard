package com.xpdustry.shard.sim.blocks.defense;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.bullet.*;
import com.xpdustry.shard.sim.combat.*;
import com.xpdustry.shard.sim.world.*;

/**
 * {@code WallBuild.collision(bullet)} (Wall.java:162), shared by the wall, the thruster and the shield wall: the hit,
 * then a surge wall's lightning, then a phase wall's deflection. Each kind sets its own {@code hit} afterwards, which
 * vanilla sets in between and nothing reads before the end. The random draws are vanilla's, sound pitches included.
 *
 * @param lightningChance {@code lightningChance}, -1 for none
 * @param chanceDeflect {@code chanceDeflect}, -1 for none
 */
record WallHits(float lightningChance, float lightningDamage, int lightningLength, float chanceDeflect){
    static final WallHits NONE = new WallHits(-1f, 20f, 17, -1f);

    /** The block's wall parameters, vanilla's defaults for toy content. */
    static WallHits of(BlockDef block){
        if(!block.baked()) return NONE;
        var fields = block.fields();
        return new WallHits(fields.f("lightningChance"), fields.f("lightningDamage"), fields.i("lightningLength"), fields.f("chanceDeflect"));
    }

    /** Whether the bullet is removed; false when it was deflected. */
    boolean collision(Building wall, Bullet bullet){
        BulletCollider.standard(wall, bullet);
        World world = wall.world();
        Rand rand = world.rand();
        // create lightning if necessary
        if(lightningChance > 0f && Mathf.chance(rand, lightningChance)){
            Lightning.create(world, wall.team, null, lightningDamage, wall.x(), wall.y(), bullet.rotation() + 180f, lightningLength);
            // lightningSound.at(tile, pitch)
            Mathf.random(rand, 0.9f, 1.1f);
        }
        // deflect bullets if necessary
        if(chanceDeflect > 0f){
            // slow bullets are not deflected
            if(bullet.speed() <= 0.1f || !bullet.type().reflectable) return true;
            // bullet reflection chance depends on bullet damage
            if(!Mathf.chance(rand, chanceDeflect / bullet.damage())) return true;
            // deflectSound.at(tile, pitch)
            Mathf.random(rand, 0.9f, 1.1f);
            // translate bullet back to where it was upon collision
            bullet.trns(-bullet.velX(), -bullet.velY());
            float penX = Math.abs(wall.x() - bullet.x()), penY = Math.abs(wall.y() - bullet.y());
            if(penX > penY){
                bullet.vel(-bullet.velX(), bullet.velY());
            }else{
                bullet.vel(bullet.velX(), -bullet.velY());
            }
            bullet.owner(wall);
            bullet.team(wall.team);
            bullet.time(bullet.time() + 1f);
            // disable bullet collision by returning false
            return false;
        }
        return true;
    }
}
