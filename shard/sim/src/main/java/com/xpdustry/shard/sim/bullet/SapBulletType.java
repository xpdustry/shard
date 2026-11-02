package com.xpdustry.shard.sim.bullet;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.combat.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.unit.*;

/**
 * {@code SapBulletType}: an instant beam of a random length that hits the first enemy on it ({@link Lasers#linecast})
 * and heals its owner by part of the damage it can deal. The target it drew to ({@code b.data}) is drawing only.
 */
@BulletKind("sap-bullet-type")
public final class SapBulletType extends BulletType{
    public float length, lengthRand, sapStrength;

    @Override
    protected void load(BulletDef def, ContentEpoch content){
        super.load(def, content);
        length = def.length();
        lengthRand = def.lengthRand();
        sapStrength = def.sapStrength();
    }

    @Override
    public void init(Bullet b){
        super.init(b);
        float len = Mathf.random(b.world().rand(), length, length + lengthRand);
        Entity target = Lasers.linecast(b, b.x(), b.y(), b.rotation(), len);
        if(target != null){
            float health = target instanceof Unit u ? u.health : ((Building)target).health;
            float result = Math.max(Math.min(health, damage), 0);
            Targets.heal(b.owner(), result * sapStrength);
        }
        if(target instanceof Unit hit){
            // hit.collision(b, …) only tells its controller (WP54)
            BulletUpdate.collision(b, hit, hit.x, hit.y);
        }else if(target instanceof Building tile){
            if(BulletCollider.collide(tile, b)){
                BulletCollider.collision(tile, b);
                hit(b, tile.x(), tile.y());
            }
        }
    }
}
