package com.xpdustry.shard.sim.unit.ability;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.spatial.*;
import com.xpdustry.shard.sim.unit.*;

/**
 * Vanilla's {@code ShieldRegenFieldAbility} (ShieldRegenFieldAbility.java): every {@code reload} ticks, raises the
 * shield of each unit of its team in range by {@code amount}, up to {@code max}; if any shield rose, its wave sound
 * draws a pitch.
 */
@AbilityKind("shield-regen-field-ability")
public final class ShieldRegenFieldAbility extends Ability{
    private float amount, max, reload, range;

    @Override
    protected void load(AbilityDef def, int index, ContentEpoch content){
        super.load(def, index, content);
        amount = def.amount();
        max = def.max();
        reload = def.reload();
        range = def.range();
    }

    @Override
    public UnitAbility create(){
        return new Timed();
    }

    @Override
    public void update(Unit unit, UnitAbility ability){
        var state = (Timed)ability;
        state.timer += 1f;
        if(state.timer >= reload){
            boolean[] applied = {false};
            SpatialIndex index = SpatialIndex.of(unit.world());
            if(index != null){
                index.nearby(unit.team, unit.x, unit.y, range, other -> {
                    if(other.shield < max){
                        other.shield = Math.min(other.shield + amount, max);
                        applied[0] = true;
                    }
                });
            }
            // sound.at(unit, 1f + Mathf.range(0.1f), soundVolume): the pitch draws even headless
            if(applied[0]) Mathf.range(unit.world().rand(), 0.1f);
            state.timer = 0f;
        }
    }
}
