package com.xpdustry.shard.sim.bullet;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;

/**
 * {@code RailBulletType}: an instant line ({@link Lasers#collideLine}) that loses health's worth of damage at every
 * pierce and stops, keeping in {@code fdata} how far it got. It only collides with other teams' buildings. Its point,
 * line and end effects are client-side.
 */
@BulletKind("rail-bullet-type")
public final class RailBulletType extends BulletType{
    public float length;

    @Override
    protected void load(BulletDef def, ContentEpoch content){
        super.load(def, content);
        length = def.length();
    }

    @Override
    public void handlePierce(Bullet b, float initialHealth, float x, float y){
        float sub = Math.max(initialHealth * pierceDamageFactor, 0);
        if(b.damage() <= 0){
            b.fdata(Math.min(b.fdata(), b.dst(x, y)));
            return;
        }
        // pierceEffect.at(x, y, b.rotation())
        // subtract health from each consecutive pierce
        b.damage(b.damage() - Math.min(b.damage(), sub));
        // the bullet was stopped: decrease the furthest distance
        if(b.damage() <= 0f) b.fdata(Math.min(b.fdata(), b.dst(x, y)));
    }

    @Override
    public void init(Bullet b){
        super.init(b);
        b.fdata(length);
        Lasers.collideLine(b, b.team(), b.x(), b.y(), b.rotation(), length, false, false, pierceCap);
    }

    @Override
    public boolean testCollision(Bullet bullet, Building tile){
        return bullet.team() != tile.team;
    }

    @Override
    public void hitTile(Bullet b, Building build, float x, float y, float initialHealth, boolean direct){
        handlePierce(b, initialHealth, x, y);
    }
}
