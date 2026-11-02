package com.xpdustry.shard.sim.bullet;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.sim.combat.*;

/** {@code LightningBulletType}: a bolt of lightning from where it is made, at the type's damage. */
@BulletKind("lightning-bullet-type")
public final class LightningBulletType extends BulletType{
    @Override
    public void init(Bullet b){
        super.init(b);
        Lightning.create(b, damage, b.x(), b.y(), b.rotation(), lightningLength + Mathf.random(b.world().rand(), lightningLengthRand));
    }
}
