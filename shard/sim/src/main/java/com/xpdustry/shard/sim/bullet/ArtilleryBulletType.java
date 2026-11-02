package com.xpdustry.shard.sim.bullet;

import com.xpdustry.shard.content.*;

/**
 * {@code ArtilleryBulletType}: flies over everything ({@code collides} off) and hits where it lands. Its update spawns
 * a trail effect on timer 0, which a server doesn't show but still runs, since the timer stays with the pooled
 * bullet for its next user.
 */
@BulletKind("artillery-bullet-type")
public final class ArtilleryBulletType extends BasicBulletType{
    public float trailMult;

    @Override
    protected void load(BulletDef def, ContentEpoch content){
        super.load(def, content);
        trailMult = def.trailMult();
    }

    @Override
    public void update(Bullet b){
        super.update(b);
        // trailEffect.at(…) when it fires
        b.timer(0, (3 + b.fslope() * 2f) * trailMult);
    }
}
