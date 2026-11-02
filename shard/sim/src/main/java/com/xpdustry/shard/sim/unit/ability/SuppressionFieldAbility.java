package com.xpdustry.shard.sim.unit.ability;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.combat.*;
import com.xpdustry.shard.sim.unit.*;

/**
 * Vanilla's {@code SuppressionFieldAbility} (SuppressionFieldAbility.java): every {@code maxDelay} ticks, enemy
 * buildings within {@code range} of its offset can't be healed for {@code reload + 1} ticks
 * ({@link Damage#applySuppression}); quell and disrupt carry it. An inactive one (disrupt's second) does nothing. The
 * particles are client-side and draw nothing on a server.
 */
@AbilityKind("suppression-field-ability")
public final class SuppressionFieldAbility extends Ability{
    private float reload, maxDelay, range, x, y;
    private boolean active;

    @Override
    protected void load(AbilityDef def, int index, ContentEpoch content){
        super.load(def, index, content);
        reload = def.reload();
        maxDelay = def.maxDelay();
        range = def.range();
        x = def.x();
        y = def.y();
        active = def.active();
    }

    @Override
    public UnitAbility create(){
        return new Timed();
    }

    @Override
    public void update(Unit unit, UnitAbility ability){
        if(!active) return;
        var state = (Timed)ability;
        if((state.timer += 1f) >= maxDelay){
            Vec2 at = new Vec2(x, y).rotate(unit.rotation - 90f).add(unit.x, unit.y);
            // applyParticleChance only spaces the particles out
            Damage.applySuppression(unit.world(), unit.team, at.x, at.y, range, reload, maxDelay, 0f);
            state.timer = 0f;
        }
    }
}
