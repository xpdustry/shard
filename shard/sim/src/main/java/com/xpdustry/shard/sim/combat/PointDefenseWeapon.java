package com.xpdustry.shard.sim.combat;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.sim.bullet.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code PointDefenseWeapon}: aims at the nearest hittable enemy bullet and chips its damage away, removing
 * it once the damage is gone. The target is a bullet, which the mount keeps as its pooled object
 * ({@link WeaponMount#targetBullet}), as vanilla's mount keeps the object: a removed bullet stays a target until the
 * pool resets it, and a reused object is the new bullet. Targets come from the bullet tree in its own order, which, as
 * vanilla's, still holds the bullets removed since its fill ({@link Bullets#intersectQuad}): bullets a volley fires
 * from one point tie on distance, and the tree's order picks among them.
 */
@WeaponKind("point-defense-weapon")
public class PointDefenseWeapon extends Weapon{
    @Override
    void updateTarget(Unit unit, WeaponMount mount, float mountX, float mountY, boolean can){
        World world = unit.world();
        mount.target = null;
        if((mount.retarget -= 1f) <= 0f){
            mount.targetBullet = findBullet(unit, mountX, mountY, bullet.range);
            mount.retarget = mount.targetBullet == -1 ? targetInterval : targetSwitchInterval;
        }
        Bullet target = Bullet.ofObject(world, mount.targetBullet);
        if(mount.targetBullet != -1 && invalid(unit, target, bullet.range)){
            mount.targetBullet = -1;
            target = null;
        }
        boolean shoot = false;
        if(target != null){
            shoot = Mathf.within(target.x(), target.y(), mountX, mountY, bullet.range + Math.abs(shootY) + target.hitSize() / 2f) && can;
            mount.aimX = target.x();
            mount.aimY = target.y();
        }
        mount.shoot = mount.rotate = shoot;
    }

    /**
     * {@code findTarget}: {@code Groups.bullet.intersect(...).min(b -> b.team != unit.team && b.type().hittable,
     * b -> b.dst2(x, y))}, the last of the nearest in tree order, as its pooled object, or -1.
     */
    int findBullet(Unit unit, float x, float y, float range){
        World world = unit.world();
        Bullets bullets = world.bullets();
        // don't waste time for empty groups
        if(bullets.size() == 0) return -1;
        BulletTypes types = bullets.types(world);
        int[] best = {-1};
        float[] min = {Float.MAX_VALUE};
        bullets.intersectQuad(x - range, y - range, range * 2, range * 2, (table, row) -> {
            if(table.team[row] == unit.team || !types.get(table.type[row]).hittable) return;
            float dst = Mathf.dst2(table.x[row], table.y[row], x, y);
            if(dst <= min[0]){
                best[0] = table.object[row];
                min[0] = dst;
            }
        });
        return best[0];
    }

    /** {@code checkTarget}: dropped when freed, out of range of the unit, on its team, or not hittable. */
    boolean invalid(Unit unit, Bullet target, float range){
        return !(target != null && Mathf.within(target.x(), target.y(), unit.x, unit.y, range) && target.team() != unit.team
            && target.type().hittable);
    }

    @Override
    protected void shoot(Unit unit, WeaponMount mount, float shootX, float shootY, float rotation){
        World world = unit.world();
        Bullet target = Bullet.ofObject(world, mount.targetBullet);
        if(target == null) return;
        // not sure whether it should multiply by the damageMultiplier of the unit
        float bulletDamage = bullet.damage * unit.statuses.damageMultiplier * UnitCalls.rules(world).unitDamage(unit.team);
        if(target.damage() > bulletDamage){
            target.damage(target.damage() - bulletDamage);
        }else{
            target.remove();
        }
        // shootSound.at(shootX, shootY, Mathf.random(0.9f, 1.1f))
        Mathf.random(world.rand(), 0.9f, 1.1f);
        mount.recoil = 1f;
        mount.heat = 1f;
    }
}
