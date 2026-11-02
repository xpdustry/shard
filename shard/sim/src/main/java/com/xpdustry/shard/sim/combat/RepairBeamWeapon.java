package com.xpdustry.shard.sim.combat;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.spatial.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code RepairBeamWeapon}: heals instead of shooting. An autonomous beam heals the nearest damaged ally
 * unit; a controlled one heals the first damaged ally building along its aim. Its {@code HealBeamMount} state is
 * {@link WeaponMount#strength}, {@link WeaponMount#effectTimer} and the beam's end ({@link WeaponMount#lastEndX},
 * {@link WeaponMount#lastEndY}); the end's wander is drawing only.
 *
 * <p>Not ported: an autonomous beam with {@code targetBuildings}, which no vanilla content has
 * ({@code Units.findAllyTile} is the building indexer's).
 */
@WeaponKind("repair-beam-weapon")
public class RepairBeamWeapon extends Weapon{
    public boolean targetBuildings, targetUnits;
    public float repairSpeed, fractionRepairSpeed, recentDamageMultiplier;

    @Override
    protected void load(WeaponDef def, int index, com.xpdustry.shard.sim.bullet.BulletTypes bullets){
        super.load(def, index, bullets);
        targetBuildings = def.fields().b("targetBuildings");
        targetUnits = def.fields().b("targetUnits");
        repairSpeed = def.fields().f("repairSpeed");
        fractionRepairSpeed = def.fields().f("fractionRepairSpeed");
        recentDamageMultiplier = def.fields().f("recentDamageMultiplier");
    }

    @Override
    protected Entity findTarget(Unit unit, float x, float y, float range, boolean air, boolean ground){
        SpatialIndex index = SpatialIndex.of(unit.world());
        return targetUnits && index != null ? index.closest(unit.team, x, y, range, u -> u != unit && u.damaged()) : null;
    }

    @Override
    protected boolean checkTarget(Unit unit, Entity target, float x, float y, float range){
        boolean valid = Targets.within(target, unit.x, unit.y, range + unit.hitSize / 2f) && Targets.team(target) == unit.team
            && (target instanceof Unit u ? u.damaged() : ((Building)target).damaged()) && Targets.isValid(target);
        return !valid;
    }

    @Override
    protected void shoot(Unit unit, WeaponMount mount, float shootX, float shootY, float rotation){
        // does nothing, shooting is handled in update()
    }

    @Override
    public void update(Unit unit, WeaponMount mount){
        super.update(unit, mount);
        World world = unit.world();
        float weaponRotation = unit.rotation - 90;
        float wx = unit.x + Angles.trnsx(weaponRotation, x, y), wy = unit.y + Angles.trnsy(weaponRotation, x, y);
        boolean canShoot = mount.shoot;
        if(!autoTarget){
            mount.target = null;
            if(canShoot){
                mount.lastEndX = mount.aimX;
                mount.lastEndY = mount.aimY;
                if(!rotate && !Angles.within(Angles.angle(wx, wy, mount.aimX, mount.aimY), unit.rotation, shootCone)) canShoot = false;
            }
            // limit range
            var end = new Vec2(mount.lastEndX, mount.lastEndY).sub(wx, wy).limit(range()).add(wx, wy);
            mount.lastEndX = end.x;
            mount.lastEndY = end.y;
            if(targetBuildings){
                // snap to the closest building
                Geometry.raycast(Math.round(wx / 8f), Math.round(wy / 8f), Math.round(end.x / 8f), Math.round(end.y / 8f), (tx, ty) -> {
                    Building build = world.buildings().at(tx, ty);
                    if(build != null && build.team == unit.team && build.damaged()){
                        mount.target = build;
                        mount.lastEndX = tx * 8f;
                        mount.lastEndY = ty * 8f;
                        return true;
                    }
                    return false;
                });
            }
        }
        mount.strength = Mathf.lerpDelta(mount.strength, Mathf.num(autoTarget ? mount.target != null : canShoot), 0.2f);
        // the heal effect, periodically
        if(canShoot && mount.target instanceof Building b && b.damaged() && (mount.effectTimer += 1f) >= reload){
            mount.effectTimer = 0f;
        }
        if(canShoot && mount.target != null){
            Entity target = mount.target;
            float maxHealth = target instanceof Unit u ? u.maxHealth : ((Building)target).maxHealth;
            float baseAmount = repairSpeed * mount.strength + fractionRepairSpeed * mount.strength * maxHealth / 100f;
            float scale = target instanceof Building b && b.wasRecentlyDamaged() ? recentDamageMultiplier : 1f;
            Targets.heal(target, scale * baseAmount);
        }
    }
}
