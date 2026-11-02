package com.xpdustry.shard.sim.bullet;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.combat.*;

/**
 * {@code FireBulletType}, the fireball an explosion throws: a random speed when it starts, then each tick a chance to
 * set its tile alight ({@link Hazards#fire}) and two chances of a client effect, which draw all the same.
 */
@BulletKind("fire-bullet-type")
public final class FireBulletType extends BulletType{
    public float velMin, velMax, fireTrailChance, fireEffectChance, fireEffectChance2;

    @Override
    protected void load(BulletDef def, ContentEpoch content){
        super.load(def, content);
        velMin = def.velMin();
        velMax = def.velMax();
        fireTrailChance = def.fireTrailChance();
        fireEffectChance = def.fireEffectChance();
        fireEffectChance2 = def.fireEffectChance2();
    }

    /** {@code b.vel.setLength(Mathf.random(velMin, velMax))}. */
    @Override
    public void init(Bullet b){
        super.init(b);
        b.vel(b.vel().setLength(Mathf.random(b.world().rand(), velMin, velMax)));
    }

    @Override
    public void update(Bullet b){
        super.update(b);
        Rand rand = b.world().rand();
        if(Mathf.chanceDelta(rand, fireTrailChance)) Hazards.of(b.world()).fire(tileWorld(b.world(), b.x(), b.y()));
        // trailEffect.at, trailEffect2.at
        Mathf.chanceDelta(rand, fireEffectChance);
        Mathf.chanceDelta(rand, fireEffectChance2);
    }
}
