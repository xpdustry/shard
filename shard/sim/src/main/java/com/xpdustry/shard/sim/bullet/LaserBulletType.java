package com.xpdustry.shard.sim.bullet;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.combat.*;
import com.xpdustry.shard.sim.world.*;

/**
 * {@code LaserBulletType}: an instant beam. When it is made it damages everything along its length
 * ({@link Lasers#collideLaser}) and, with {@code lightningSpacing}, queues pairs of lightning along the beam, one pair
 * every {@code lightningDelay} ticks, which strike only if the bullet object is still this kind's then. Its
 * {@code init} doesn't run the base type's (no spawn bullets, no {@code killShooter}).
 */
@BulletKind("laser-bullet-type")
public final class LaserBulletType extends BulletType{
    public float length, lightningSpacing, lightningDelay, lightningAngleRand;
    public boolean largeHit;

    @Override
    protected void load(BulletDef def, ContentEpoch content){
        super.load(def, content);
        length = def.length();
        largeHit = def.largeHit();
        lightningSpacing = def.lightningSpacing();
        lightningDelay = def.lightningDelay();
        lightningAngleRand = def.lightningAngleRand();
    }

    @Override
    public void init(Bullet b){
        float resultLength = Lasers.collideLaser(b, length, largeHit, laserAbsorb, pierceCap), rot = b.rotation();
        // laserEffect.at(…)
        if(lightningSpacing > 0){
            int idx = 0;
            for(float i = 0; i <= resultLength; i += lightningSpacing){
                float cx = b.x() + Angles.trnsx(rot, i), cy = b.y() + Angles.trnsy(rot, i);
                int f = idx++;
                for(int s : new int[]{-1, 1}){
                    b.world().timers().run(f * lightningDelay, new Strike(b.object(), id(), cx, cy, rot + 90 * s));
                }
            }
        }
    }

    /**
     * {@code Time.run(f * lightningDelay, () -> { if(b.isAdded() && b.type == this) Lightning.create(…) })}. The closure
     * holds the pooled bullet object, so this holds its {@link Bullets#object} number, and strikes for whichever live
     * bullet is that object then.
     */
    public record Strike(int object, int type, float x, float y, float angle) implements Timers.Task{
        @Override
        public void run(World world){
            Bullets bullets = world.bullets();
            int row = bullets.rowOfObject(object);
            if(row < 0 || bullets.type[row] != type) return;
            var laser = (LaserBulletType)bullets.types(world).get(type);
            Bullet b = new Bullet(world, bullets, row);
            Rand rand = world.rand();
            float angle = this.angle + Mathf.range(rand, laser.lightningAngleRand);
            int length = laser.lightningLength + Mathf.random(rand, laser.lightningLengthRand);
            Lightning.create(b, laser.lightningDamage < 0 ? laser.damage : laser.lightningDamage, x, y, angle, length);
        }
    }
}
