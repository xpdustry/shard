package com.xpdustry.shard.sim.bullet;

import com.xpdustry.shard.sim.ai.team.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.combat.*;

/**
 * How a building takes a bullet: {@code BuildingComp.collide(bullet)} and {@code collision(bullet)}. A building kind
 * that changes them (walls deflect and arc lightning, target dummies pass hits to their unit, logic blocks let
 * privileged ones pass) implements this interface; every other building gets vanilla's {@link #standard}.
 */
public interface BulletCollider{
    /** {@code collide(bullet)}: whether the bullet can hit this building at all. */
    default boolean collide(Bullet bullet){
        return true;
    }

    /** {@code collision(bullet)}: takes the hit; returns whether the bullet should be removed. */
    default boolean collision(Bullet bullet){
        return standard((Building)this, bullet);
    }

    /** {@code collide(bullet)} for any building. */
    static boolean collide(Building building, Bullet bullet){
        return !(building instanceof BulletCollider collider) || collider.collide(bullet);
    }

    /** {@code collision(bullet)} for any building. */
    static boolean collision(Building building, Bullet bullet){
        return building instanceof BulletCollider collider ? collider.collision(bullet) : standard(building, bullet);
    }

    /**
     * {@code BuildingComp.collision} (BuildingComp.java:1761): the bullet's building damage, less armor unless it
     * pierces armor, taken from the bullet's team. Always removes the bullet.
     */
    static boolean standard(Building building, Bullet bullet){
        BulletType type = bullet.type();
        float damage = type.buildingDamage(bullet);
        if(!type.pierceArmor){
            damage = Damage.applyArmor(damage, building.info().armor * type.armorMultiplier * type.blockArmorMultiplier);
        }
        building.damage(bullet.team(), damage);
        // damage(bullet, team, amount) fires BuildDamageEvent (BuildingComp.java:1787)
        TeamAis.buildDamaged(building);
        return true;
    }
}
