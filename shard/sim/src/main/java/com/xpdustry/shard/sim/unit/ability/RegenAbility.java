package com.xpdustry.shard.sim.unit.ability;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.unit.*;

/** Vanilla's {@code RegenAbility} (RegenAbility.java): heals a share of the maximum health and a flat amount each tick. */
@AbilityKind("regen-ability")
public final class RegenAbility extends Ability{
    /** Percent of the maximum health, and a flat amount, per tick. */
    private float percentAmount, amount;

    @Override
    protected void load(AbilityDef def, int index, ContentEpoch content){
        super.load(def, index, content);
        percentAmount = def.percentAmount();
        amount = def.amount();
    }

    @Override
    public void update(Unit unit, UnitAbility state){
        // (maxHealth * percentAmount / 100 + amount) * Time.delta, at Δ = 1
        unit.heal(unit.maxHealth * percentAmount / 100f + amount);
    }
}
