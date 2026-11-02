package com.xpdustry.shard.sim.bullet;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;

/** {@code ContinuousLaserBulletType}: a continuous beam that shrinks over its last {@code fadeTime} ticks. */
@BulletKind("continuous-laser-bullet-type")
public final class ContinuousLaserBulletType extends ContinuousBulletType{
    public float fadeTime;

    @Override
    protected void load(BulletDef def, ContentEpoch content){
        super.load(def, content);
        fadeTime = def.fadeTime();
    }

    /** Vanilla quirk kept: the fade starts from the bullet's lifetime but ends at the type's. */
    @Override
    public float currentLength(Bullet b){
        float fout = Mathf.clamp(b.time() > b.lifetime() - fadeTime ? 1f - (b.time() - (lifetime - fadeTime)) / fadeTime : 1f);
        return length * fout;
    }
}
