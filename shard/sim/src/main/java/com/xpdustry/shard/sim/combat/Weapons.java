package com.xpdustry.shard.sim.combat;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.bullet.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * {@code WeaponsComp}: each tick every mount's weapon {@linkplain Weapon#update updates}, in mount order; and the
 * component's methods controllers and AI call: {@link #controlWeapons}, {@link #aim}, {@link #canShoot}. The mounts
 * and the aim live on the {@link Unit}. Every unit class has the component; it does nothing without weapons.
 */
@Register
public final class Weapons implements UnitComponent{
    @Override
    public String name(){
        return "WeaponsComp";
    }

    @Override
    public boolean applies(UnitParams params){
        return params.weaponCount() > 0;
    }

    @Override
    public void update(Unit unit){
        for(WeaponMount mount : unit.mounts){
            Weapon.of(unit, mount).update(unit, mount);
        }
    }

    /** {@code UnitComp.canShoot()}: not disarmed, and not boosting (a type that can boost cannot shoot flying). */
    public static boolean canShoot(Unit unit){
        return !unit.statuses.disarmed && !(unit.params().canBoost() && unit.isFlying());
    }

    /** {@code controlWeapons(rotateShoot)}. */
    public static void controlWeapons(Unit unit, boolean rotateShoot){
        controlWeapons(unit, rotateShoot, rotateShoot);
    }

    /** {@code controlWeapons(rotate, shoot)}: the controllable mounts rotate and shoot as told. */
    public static void controlWeapons(Unit unit, boolean rotate, boolean shoot){
        for(WeaponMount mount : unit.mounts){
            if(Weapon.of(unit, mount).controllable){
                mount.rotate = rotate;
                mount.shoot = shoot;
            }
        }
        unit.isRotate = rotate;
        unit.isShooting = shoot;
    }

    /** {@code aim(x, y)}. */
    public static void aim(Unit unit, float x, float y){
        aim(unit, x, y, false);
    }

    /** {@code aim(x, y, clearTarget)}: the controllable mounts aim at (x, y), pushed out to the type's {@code aimDst}. */
    public static void aim(Unit unit, float x, float y, boolean clearTarget){
        var to = new Vec2(x, y).sub(unit.x, unit.y);
        float aimDst = Arms.of(unit).aimDst();
        if(to.len() < aimDst) to.setLength(aimDst);
        x = to.x + unit.x;
        y = to.y + unit.y;
        for(WeaponMount mount : unit.mounts){
            if(Weapon.of(unit, mount).controllable){
                if(clearTarget) mount.target = null;
                mount.aimX = x;
                mount.aimY = y;
            }
        }
        unit.aimX = x;
        unit.aimY = y;
    }

    /** {@code setWeaponRotation(rotation)}. */
    public static void setWeaponRotation(Unit unit, float rotation){
        for(WeaponMount mount : unit.mounts) mount.rotation = rotation;
    }

    /** A replay accessor: {@code isShooting}, the synced state a viewer draws muzzle flashes from. */
    public static boolean isShooting(Unit unit){
        return unit.isShooting;
    }

    /**
     * The death shots of {@code UnitComp.destroy} (UnitComp.java:911), from the {@link UnitHooks#destroyed} hook: each
     * weapon that shoots on death, unless its bullet kills its shooter and it already fired, reloads at once and
     * updates shooting.
     */
    public static void shootOnDeath(Unit unit){
        for(WeaponMount mount : unit.mounts){
            Weapon weapon = Weapon.of(unit, mount);
            if(weapon.shootOnDeath && !(weapon.bullet != null && weapon.bullet.killShooter && mount.totalShots > 0)){
                if(weapon.hasShootOnDeathEffect && !unit.hasTarget) mount.allowShootEffects = false;
                mount.reload = 0f;
                mount.shoot = true;
                weapon.update(unit, mount);
            }
        }
    }

    /**
     * {@code WeaponsComp.remove}, from the {@link UnitHooks#removed} hook: a continuous bullet the unit owns ends 10
     * frames later.
     */
    public static void removed(Unit unit){
        World world = unit.world();
        for(WeaponMount mount : unit.mounts){
            if(!Weapon.of(unit, mount).continuous || mount.bullet == -1) continue;
            Bullet bullet = Bullet.ofObject(world, mount.bullet);
            if(bullet != null && bullet.owner() == unit){
                bullet.time(bullet.lifetime() - 10f);
                mount.bullet = -1;
            }
        }
    }
}
