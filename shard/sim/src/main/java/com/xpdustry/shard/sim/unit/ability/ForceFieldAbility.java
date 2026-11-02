package com.xpdustry.shard.sim.unit.ability;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.bullet.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code ForceFieldAbility} (ForceFieldAbility.java): a polygon shield around the unit, held in the unit's
 * own {@code shield}, which damage drains first. It starts full ({@code created}), regenerates {@code regen} a tick up
 * to {@code max} (both scaled by the team's unit health), and while it holds, it absorbs the enemy bullets inside the
 * polygon, each draining {@code shieldDamage} and drawing the hit sound's pitch. Breaking costs {@code cooldown *
 * regen} more. The radius grows from nothing each time it comes back. Quasar and oct carry it.
 *
 * <p>Vanilla's quirk, kept: unlike the force projector, it doesn't skip bullets already absorbed. Bullets removed
 * since the bullet tree was filled are still in it ({@link Bullets#intersectQuad}), so a bullet another shield took
 * this frame is absorbed again, draining this one too.
 *
 * <p>{@code alpha} only draws the hit flash, so it is not kept.
 */
@AbilityKind("force-field-ability")
public final class ForceFieldAbility extends Ability{
    private float radius, regen, max, cooldown, rotation;
    private int sides;

    @Override
    protected void load(AbilityDef def, int index, ContentEpoch content){
        super.load(def, index, content);
        radius = def.radius();
        regen = def.regen();
        max = def.max();
        cooldown = def.cooldown();
        sides = def.sides();
        rotation = def.rotation();
    }

    private float scaledMax(Unit unit){
        return max * UnitCalls.rules(unit.world()).unitHealth(unit.team);
    }

    @Override
    public UnitAbility create(){
        return new State();
    }

    @Override
    public void created(Unit unit, UnitAbility state){
        unit.shield = scaledMax(unit);
    }

    @Override
    public void update(Unit unit, UnitAbility ability){
        var state = (State)ability;
        if(unit.shield <= 0f && !state.wasBroken){
            unit.shield -= cooldown * regen;
        }
        // Time.delta * regen, at Δ = 1
        if(unit.shield < scaledMax(unit)) unit.shield += regen;
        state.wasBroken = unit.shield <= 0f;

        if(unit.shield > 0){
            state.radiusScale = Mathf.lerpDelta(state.radiusScale, 1f, 0.06f);
            float realRad = state.radiusScale * radius;
            World world = unit.world();
            var found = new ArrayList<Bullet>();
            world.bullets().intersectQuad(unit.x - realRad, unit.y - realRad, realRad * 2f, realRad * 2f, (table, row) -> found.add(Bullet.ofObject(world, table.object[row])));
            // the consumer runs inside vanilla's traversal; absorbing removes nothing from the tree, so after it is the same
            Rand rand = world.rand();
            for(Bullet b : found){
                if(b.team() != unit.team && b.type().absorbable && Intersector.isInRegularPolygon(sides, unit.x, unit.y, realRad, rotation, b.x(), b.y())
                    && unit.shield > 0){
                    b.absorb();
                    // hitSound.at(b.x, b.y, 1f + Mathf.range(0.1f), hitSoundVolume): the pitch draws even headless
                    Mathf.range(rand, 0.1f);
                    unit.shield -= b.damage() * b.type().shieldDamageMultiplier;
                }
            }
        }else{
            state.radiusScale = 0f;
        }
    }

    /** {@code radiusScale} and {@code wasBroken}; a fresh copy starts broken, so its first update can't break it. */
    public static final class State extends UnitAbility{
        public float radiusScale;
        public boolean wasBroken = true;

        @Override
        public UnitAbility copy(){
            State out = copyInto(new State());
            out.radiusScale = radiusScale;
            out.wasBroken = wasBroken;
            return out;
        }

        @Override
        public void hash(Hasher hasher){
            super.hash(hasher);
            hasher.f(radiusScale).bool(wasBroken);
        }

        @Override
        public void write(CheckpointOut out){
            super.write(out);
            out.f("radiusScale", radiusScale).bool("wasBroken", wasBroken);
        }

        @Override
        public void read(CheckpointIn in){
            super.read(in);
            radiusScale = in.f("radiusScale");
            wasBroken = in.bool("wasBroken");
        }
    }
}
