package com.xpdustry.shard.sim.bullet;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;

/**
 * {@code PointLaserBulletType}: a beam that only damages where it is aimed, every {@code damageInterval} ticks
 * ({@link Lasers#collidePoint}). Its update replaces the base type's: trails and interval bullets come out at the aim
 * point, and its beam effect still ticks timer 1. Vanilla quirk kept: the damage and the trail interval share timer 0.
 */
@BulletKind("point-laser-bullet-type")
public final class PointLaserBulletType extends BulletType{
    public float damageInterval, beamEffectInterval;

    @Override
    protected void load(BulletDef def, ContentEpoch content){
        super.load(def, content);
        damageInterval = def.damageInterval();
        beamEffectInterval = def.beamEffectInterval();
    }

    @Override
    public void update(Bullet b){
        updateTrailEffects(b);
        updateBulletInterval(b);
        if(b.timer(0, damageInterval)) Lasers.collidePoint(b, b.team(), b.aimX(), b.aimY());
        // beamEffect.at(b.aimX, b.aimY, …)
        b.timer(1, beamEffectInterval);
    }

    /** The trail effects at the aim point: the chance and the timer, which draw and tick on a server too. */
    @Override
    public void updateTrailEffects(Bullet b){
        if(trailChance > 0) Mathf.chanceDelta(b.world().rand(), trailChance);
        if(trailInterval > 0f) b.timer(0, trailInterval);
    }

    @Override
    public void updateBulletInterval(Bullet b){
        if(intervalBullet != null && b.time() >= intervalDelay && b.timer(2, bulletInterval)){
            float angle = b.rotation();
            Rand rand = b.world().rand();
            for(int i = 0; i < intervalBullets; i++){
                intervalBullet.create(b, b.aimX(), b.aimY(), angle + Mathf.range(rand, intervalRandomSpread) + intervalAngle + ((i - (intervalBullets - 1f) / 2f) * intervalSpread));
            }
        }
    }
}
