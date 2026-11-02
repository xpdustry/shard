package com.xpdustry.shard.sim.bullet;

import com.xpdustry.shard.content.*;

/** {@code ShrapnelBulletType}: an instant laser line ({@link Lasers#collideLaser}) when made. */
@BulletKind("shrapnel-bullet-type")
public final class ShrapnelBulletType extends BulletType{
    public float length;
    public boolean hitLarge;

    @Override
    protected void load(BulletDef def, ContentEpoch content){
        super.load(def, content);
        length = def.length();
        hitLarge = def.hitLarge();
    }

    @Override
    public void init(Bullet b){
        super.init(b);
        Lasers.collideLaser(b, length, hitLarge, laserAbsorb, pierceCap);
    }
}
