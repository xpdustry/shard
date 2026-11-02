package com.xpdustry.shard.sim.unit.ability;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.bullet.*;
import com.xpdustry.shard.sim.spatial.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code ShieldArcAbility} (ShieldArcAbility.java): an arc of shield in front of the unit, its strength in the
 * ability's {@code data} (synced). It starts full ({@code created}) and regenerates {@code regen} a tick up to
 * {@code max}, both scaled by the team's unit health. While it holds (and, if {@code whenShooting}, while the unit
 * shoots), enemy bullets crossing the arc band are deflected (with {@code chanceDeflect}: bounced back as the unit's,
 * a sound pitch drawn) or absorbed (a hit sound pitch drawn), each draining their shield damage; one strong enough to
 * break it costs {@code cooldown * regen} more. Enemy missile units crossing it die ({@code Call.unitSafeDeath}) and
 * drain twice their health; other enemy units are pushed out, drawing their spark's chance. Tecta carries it.
 *
 * <p>As the force field, it doesn't skip bullets already absorbed: a removed bullet still in the tree counts again.
 * {@code widthScale} and {@code alpha} only draw it, so they are not kept.
 */
@AbilityKind("shield-arc-ability")
public final class ShieldArcAbility extends Ability{
    private float radius, regen, max, cooldown, angle, angleOffset, x, y, width, chanceDeflect, reflectBuildingDamage;
    private float reflectVel, reflectTime, missileUnitMultiplier;
    private boolean whenShooting, pushUnits, pushDiffLayer;

    @Override
    protected void load(AbilityDef def, int index, ContentEpoch content){
        super.load(def, index, content);
        radius = def.radius();
        regen = def.regen();
        max = def.max();
        cooldown = def.cooldown();
        angle = def.angle();
        angleOffset = def.angleOffset();
        x = def.x();
        y = def.y();
        whenShooting = def.whenShooting();
        width = def.width();
        chanceDeflect = def.chanceDeflect();
        reflectBuildingDamage = def.reflectBuildingDamage();
        reflectVel = def.reflectVel();
        reflectTime = def.reflectTime();
        missileUnitMultiplier = def.missileUnitMultiplier();
        pushUnits = def.pushUnits();
        pushDiffLayer = def.pushDiffLayer();
    }

    private float scaledMax(Unit unit){
        return max * UnitCalls.rules(unit.world()).unitHealth(unit.team);
    }

    @Override
    public void created(Unit unit, UnitAbility state){
        state.data = scaledMax(unit);
    }

    @Override
    public void update(Unit unit, UnitAbility state){
        // Time.delta * regen, at Δ = 1
        if(state.data < scaledMax(unit)) state.data += regen;
        boolean active = state.data > 0 && (unit.isShooting || !whenShooting);
        if(!active) return;

        World world = unit.world();
        Vec2 pos = new Vec2(x, y).rotate(unit.rotation - 90f).add(unit.x, unit.y);
        float reach = radius + width;
        var found = new ArrayList<Bullet>();
        world.bullets().intersectQuad(pos.x - reach, pos.y - reach, reach * 2f, reach * 2f, (table, row) -> found.add(Bullet.ofObject(world, table.object[row])));
        // the consumer runs inside vanilla's traversal; nothing it does moves a bullet in the tree
        for(Bullet b : found) bullet(unit, state, pos, b);

        SpatialIndex index = SpatialIndex.of(world);
        if(index != null) index.nearbyEnemies(unit.team, pos.x - reach, pos.y - reach, reach * 2f, reach * 2f, other -> unit(unit, state, pos, other));
    }

    /** Whether something at (px, py) that moved by (dx, dy) this tick crosses the arc's band. */
    private boolean crosses(Unit unit, Vec2 pos, float px, float py, float dx, float dy){
        float inner = radius - width, outer = radius + width;
        float facing = unit.rotation + angleOffset;
        return !(Mathf.within(px, py, pos.x, pos.y, inner) && Mathf.within(pos.x, pos.y, px - dx, py - dy, inner))
            && (Mathf.within(px + dx, py + dy, pos.x, pos.y, outer) || Mathf.within(px, py, pos.x, pos.y, outer))
            && (Angles.within(Angles.angle(pos.x, pos.y, px, py), facing, angle / 2f)
                || Angles.within(Angles.angle(pos.x, pos.y, px + dx, py + dy), facing, angle / 2f));
    }

    /** {@code shieldConsumer}. */
    private void bullet(Unit unit, UnitAbility state, Vec2 pos, Bullet b){
        Bullets t = b.table();
        int row = b.row();
        BulletType type = b.type();
        if(b.team() == unit.team || !type.absorbable || state.data <= 0 || !crosses(unit, pos, b.x(), b.y(), t.deltaX[row], t.deltaY[row])) return;
        Rand rand = unit.world().rand();
        if(chanceDeflect > 0f && b.speed() >= 0.1f && type.reflectable && Mathf.chance(rand, chanceDeflect)){
            // deflectSound.at(paramPos, Mathf.random(0.9f, 1.1f)): the pitch draws even headless
            Mathf.random(rand, 0.9f, 1.1f);
            // translate bullet back to where it was upon collision
            b.trns(-b.velX(), -b.velY());
            float nx = b.x() - pos.x, ny = b.y() - pos.y;
            float nlen = Mathf.len(nx, ny);
            if(nlen > 0.0001f){
                nx /= nlen;
                ny /= nlen;
            }
            float vx = b.velX(), vy = b.velY();
            float dot = vx * nx + vy * ny;
            float rx = vx - 2f * dot * nx;
            float ry = vy - 2f * dot * ny;
            float outDot = rx * nx + ry * ny;
            float normalX = outDot * nx, normalY = outDot * ny;
            float tangX = rx - normalX, tangY = ry - normalY;
            b.vel(normalX + tangX * reflectVel, normalY + tangY * reflectVel);
            b.owner(unit);
            b.team(unit.team);
            b.time(b.lifetime() * reflectTime);
            if(reflectBuildingDamage > 0f) b.table().buildingDamageMultiplier[b.row()] = reflectBuildingDamage;
        }else{
            b.absorb();
            // hitSound.at(b.x, b.y, 1f + Mathf.range(0.1f), hitSoundVolume)
            Mathf.range(rand, 0.1f);
        }
        // break shield
        if(state.data <= b.damage()) state.data -= cooldown * regen;
        // shieldDamage for consistency
        state.data -= b.damage() * type.shieldDamageMultiplier;
    }

    /** {@code unitConsumer}. */
    private void unit(Unit unit, UnitAbility state, Vec2 pos, Unit other){
        if(state.data <= 0 || !other.targetable(unit.team) || !crosses(unit, pos, other.x, other.y, other.deltaX, other.deltaY)) return;
        World world = unit.world();
        if(other.params().timedKill() && missileUnitMultiplier >= 0f){
            other.safeDeath();
            // consider missile hp and gamerule to damage the shield
            state.data -= other.health * missileUnitMultiplier * UnitCalls.rules(world).unitDamage(other.team);
        }else if(pushUnits && (pushDiffLayer || unit.isFlying() == other.isFlying())){
            float reach = radius + width;
            float overlapDst = reach - other.dst(pos.x, pos.y);
            if(overlapDst > 0){
                // only nullify velocity if it's heading towards the shield
                if(Angles.angleDist(other.angleTo(pos.x, pos.y), other.vel.angle()) < 90f) other.vel.setZero();
                // get out
                Vec2 out = new Vec2(other.x, other.y).sub(pos).setLength(overlapDst + 0.01f);
                other.move(out.x, out.y);
                // pushEffect's chance, Mathf.chanceDelta(0.3f * Time.delta)
                Mathf.chanceDelta(world.rand(), 0.3f);
            }
        }
    }
}
