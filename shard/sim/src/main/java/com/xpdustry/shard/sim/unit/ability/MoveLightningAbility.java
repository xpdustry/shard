package com.xpdustry.shard.sim.unit.ability;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.bullet.*;
import com.xpdustry.shard.sim.combat.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code MoveLightningAbility} (MoveLightningAbility.java): a unit moving faster than {@code minSpeed} has a
 * chance each tick, full at {@code maxSpeed}, to throw lightning ahead of itself from its offset, and to fire its bullet
 * if it has one, alternating sides. The chance draws every tick, even standing still. No vanilla unit carries it.
 */
@AbilityKind("move-lightning-ability")
public final class MoveLightningAbility extends Ability{
    private float damage, chance, minSpeed, maxSpeed, x, y, bulletAngle, bulletSpread;
    private int length;
    private boolean alternate;
    private BulletDef bullet;

    @Override
    protected void load(AbilityDef def, int index, ContentEpoch content){
        super.load(def, index, content);
        damage = def.damage();
        chance = def.chance();
        length = def.length();
        minSpeed = def.minSpeed();
        maxSpeed = def.maxSpeed();
        x = def.x();
        y = def.y();
        alternate = def.alternate();
        bullet = def.bullet(content);
        bulletAngle = def.bulletAngle();
        bulletSpread = def.bulletSpread();
    }

    @Override
    public UnitAbility create(){
        return new State();
    }

    @Override
    public void update(Unit unit, UnitAbility ability){
        var state = (State)ability;
        World world = unit.world();
        Rand rand = world.rand();
        float scl = Mathf.clamp((unit.vel.len() - minSpeed) / (maxSpeed - minSpeed));
        // Time.delta * chance * scl, at Δ = 1
        if(!Mathf.chance(rand, chance * scl)) return;
        float sx = unit.x + Angles.trnsx(unit.rotation, y, x * state.side), sy = unit.y + Angles.trnsy(unit.rotation, y, x * state.side);
        if(length > 0){
            Lightning.create(world, unit.team, null, damage, sx + unit.vel.x, sy + unit.vel.y, unit.rotation, length);
        }
        if(bullet != null){
            float angle = unit.rotation + bulletAngle + Mathf.range(rand, bulletSpread);
            world.bullets().types(world).get(bullet).create(world, unit, unit.team, sx, sy, angle);
        }
        if(alternate) state.side *= -1f;
    }

    /** {@code side}: the sign of the offset's x, flipped after each strike when it alternates. */
    public static final class State extends UnitAbility{
        public float side = 1f;

        @Override
        public UnitAbility copy(){
            State out = copyInto(new State());
            out.side = side;
            return out;
        }

        @Override
        public void hash(Hasher hasher){
            super.hash(hasher);
            hasher.f(side);
        }

        @Override
        public void write(CheckpointOut out){
            super.write(out);
            out.f("side", side);
        }

        @Override
        public void read(CheckpointIn in){
            super.read(in);
            side = in.f("side");
        }
    }
}
