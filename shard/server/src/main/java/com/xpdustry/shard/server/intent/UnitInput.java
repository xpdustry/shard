package com.xpdustry.shard.server.intent;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.unit.*;

import java.util.*;

/**
 * The unit methods a player's input calls that the unit core doesn't have yet: {@code WeaponsComp.controlWeapons} and
 * {@code aim}, {@code BuilderComp.canBuild}, {@code clearBuilding} and {@code removeBuild}. Each is vanilla's body;
 * {@code ItemsComp}'s are {@link Unit}'s own.
 */
final class UnitInput{
    private UnitInput(){
    }

    /** {@code controlWeapons(rotate, shoot)} (WeaponsComp.java:39): only weapons a player controls follow. */
    static void controlWeapons(Unit unit, boolean rotate, boolean shoot){
        List<WeaponDef> weapons = unit.type().weapons();
        for(WeaponMount mount : unit.mounts){
            if(weapons.get(mount.weapon).controllable()){
                mount.rotate = rotate;
                mount.shoot = shoot;
            }
        }
        unit.isRotate = rotate;
        unit.isShooting = shoot;
    }

    /** {@code aim(x, y, clearTarget)} (WeaponsComp.java:60): never closer than the type's {@code aimDst}. */
    static void aim(Unit unit, float x, float y, boolean clearTarget){
        var aim = new Vec2(x, y).sub(unit.x, unit.y);
        float aimDst = unit.type().aimDst();
        if(aim.len() < aimDst) aim.setLength(aimDst);
        x = aim.x + unit.x;
        y = aim.y + unit.y;
        List<WeaponDef> weapons = unit.type().weapons();
        for(WeaponMount mount : unit.mounts){
            if(weapons.get(mount.weapon).controllable()){
                if(clearTarget) mount.target = null;
                mount.aimX = x;
                mount.aimY = y;
            }
        }
        unit.aimX = x;
        unit.aimY = y;
    }

    /** {@code canBuild()}: the type builds, and no status stops it. */
    static boolean canBuild(Unit unit){
        return unit.type().buildSpeed() > 0 && unit.statuses.buildSpeedMultiplier > 0;
    }

    /** {@code player.isBuilder()}. */
    static boolean isBuilder(Player player){
        return player.unit() != null && canBuild(player.unit());
    }

    /** {@code removeBuild(x, y, breaking)} (BuilderComp.java:253): the first matching plan. */
    static void removeBuild(Unit unit, int x, int y, boolean breaking){
        PlanQueue plans = unit.plans;
        for(int i = 0; i < plans.size(); i++){
            BuildPlan plan = plans.get(i);
            if(plan.breaking == breaking && plan.x == x && plan.y == y){
                plans.remove(i);
                return;
            }
        }
    }
}
