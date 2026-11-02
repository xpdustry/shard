package com.xpdustry.shard.sim.unit.ability;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.combat.*;
import com.xpdustry.shard.sim.spatial.*;
import com.xpdustry.shard.sim.unit.*;

/**
 * Vanilla's {@code StatusFieldAbility} (StatusFieldAbility.java): every {@code reload} ticks (only while shooting, if
 * {@code onShoot}), applies its status to every unit of its team in range, itself included: oxynoe overclocks its
 * neighbours.
 */
@AbilityKind("status-field-ability")
public final class StatusFieldAbility extends Ability{
    private StatusDef effect;
    private float duration, reload, range;
    private boolean onShoot;

    @Override
    protected void load(AbilityDef def, int index, ContentEpoch content){
        super.load(def, index, content);
        effect = def.effect(content);
        duration = def.duration();
        reload = def.reload();
        range = def.range();
        onShoot = def.onShoot();
    }

    @Override
    public UnitAbility create(){
        return new Timed();
    }

    @Override
    public void update(Unit unit, UnitAbility ability){
        var state = (Timed)ability;
        state.timer += 1f;
        if(state.timer >= reload && (!onShoot || unit.isShooting)){
            SpatialIndex index = SpatialIndex.of(unit.world());
            if(index != null){
                StatusApply apply = StatusApply.of(unit.world());
                index.nearby(unit.team, unit.x, unit.y, range, other -> apply.apply(other, effect, duration));
            }
            state.timer = 0f;
        }
    }
}
