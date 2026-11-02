package com.xpdustry.shard.sim.combat;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.sim.build.*;
import com.xpdustry.shard.sim.unit.*;

/**
 * Vanilla's {@code BuildWeapon}: never shoots; turns to the plan being built, else faces forward. Whether the unit is
 * building and where its plan is come from construction ({@link Builder}), so the beam and the build agree.
 */
@WeaponKind("build-weapon")
public class BuildWeapon extends Weapon{
    @Override
    public void update(Unit unit, WeaponMount mount){
        mount.shoot = false;
        mount.rotate = true;
        if(Builder.activelyBuilding(unit)){
            // always aim at the build plan
            BuildPlan plan = Builder.buildPlan(unit);
            mount.aimX = Builder.planX(unit.world(), plan);
            mount.aimY = Builder.planY(unit.world(), plan);
        }else{
            // aim for front
            float weaponRotation = unit.rotation - 90 + baseRotation;
            mount.aimX = unit.x + Angles.trnsx(unit.rotation - 90, x, y) + Angles.trnsx(weaponRotation, this.shootX, this.shootY);
            mount.aimY = unit.y + Angles.trnsy(unit.rotation - 90, x, y) + Angles.trnsy(weaponRotation, this.shootX, this.shootY);
        }
        super.update(unit, mount);
    }
}
