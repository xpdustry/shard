package com.xpdustry.shard.sim.bullet;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;

/**
 * {@code ContinuousBulletType}: a beam a weapon keeps alive. Every {@code damageInterval} ticks (timer 1) it damages
 * everything along its {@link #currentLength} ({@link Lasers#collideLine}); a non-{@code continuous} one does so once,
 * when made. Its update replaces the base type's: no homing, weaving or trails, only interval bullets. Screen shake is
 * client-side.
 */
@BulletKind("continuous-bullet-type")
public class ContinuousBulletType extends BulletType{
    public float length, damageInterval;
    public boolean largeHit, continuous, timescaleDamage;

    @Override
    protected void load(BulletDef def, ContentEpoch content){
        super.load(def, content);
        length = def.length();
        damageInterval = def.damageInterval();
        largeHit = def.largeHit();
        continuous = def.continuous();
        timescaleDamage = def.timescaleDamage();
    }

    @Override
    public void init(Bullet b){
        super.init(b);
        if(!continuous) applyDamage(b);
    }

    @Override
    public void update(Bullet b){
        if(!continuous) return;
        // damage every 5 ticks
        if(b.timer(1, damageInterval)) applyDamage(b);
        updateBulletInterval(b);
    }

    /** {@code applyDamage(b)}: the line at the current length, with a building owner's time scale if asked. */
    public void applyDamage(Bullet b){
        float damage = b.damage();
        if(timescaleDamage && b.owner() instanceof Building build) b.damage(b.damage() * build.timeScale);
        Lasers.collideLine(b, b.team(), b.x(), b.y(), b.rotation(), currentLength(b), largeHit, laserAbsorb, pierceCap);
        b.damage(damage);
    }

    public float currentLength(Bullet b){
        return length;
    }
}
