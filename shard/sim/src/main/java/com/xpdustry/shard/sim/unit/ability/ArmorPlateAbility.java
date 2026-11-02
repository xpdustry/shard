package com.xpdustry.shard.sim.unit.ability;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code ArmorPlateAbility} (ArmorPlateAbility.java): while the unit shoots, a warmup rises toward 1 and adds
 * {@code warmup * healthMultiplier} to the unit's health multiplier, which its statuses reset each tick before the
 * unit core runs. No vanilla unit carries it.
 */
@AbilityKind("armor-plate-ability")
public final class ArmorPlateAbility extends Ability{
    private float healthMultiplier;

    @Override
    protected void load(AbilityDef def, int index, ContentEpoch content){
        super.load(def, index, content);
        healthMultiplier = def.healthMultiplier();
    }

    @Override
    public UnitAbility create(){
        return new State();
    }

    @Override
    public void update(Unit unit, UnitAbility ability){
        var state = (State)ability;
        state.warmup = Mathf.lerpDelta(state.warmup, unit.isShooting ? 1f : 0f, 0.1f);
        unit.statuses.healthMultiplier += state.warmup * healthMultiplier;
    }

    /** {@code warmup}. */
    public static final class State extends UnitAbility{
        public float warmup;

        @Override
        public UnitAbility copy(){
            State out = copyInto(new State());
            out.warmup = warmup;
            return out;
        }

        @Override
        public void hash(Hasher hasher){
            super.hash(hasher);
            hasher.f(warmup);
        }

        @Override
        public void write(CheckpointOut out){
            super.write(out);
            out.f("warmup", warmup);
        }

        @Override
        public void read(CheckpointIn in){
            super.read(in);
            warmup = in.f("warmup");
        }
    }
}
