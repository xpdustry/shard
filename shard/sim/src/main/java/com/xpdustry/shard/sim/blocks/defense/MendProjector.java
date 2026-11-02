package com.xpdustry.shard.sim.blocks.defense;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code MendProjector.MendBuild} (MendProjector.java): the mender and the mend projector. Its charge grows
 * with its heat, which follows efficiency (power), and at {@code reload} it heals every damaged, unsuppressed building
 * of its team in range by {@code healPercent} of their health, scaled by efficiency. The optional item (silicon,
 * phase fabric) warms {@link #phaseHeat}, which adds {@code phaseBoost} percent and {@code phaseRangeBoost} range;
 * one item lasts {@code useTime} ticks of {@code timeScale}.
 *
 * <p>Saved: {@link #heat} and {@link #phaseHeat}; {@link #charge} restarts at a fresh random on load, as vanilla's
 * field initializer draws it ({@link #constructed()}). The heal sound's pitch draw is made; effects aren't drawn.
 */
@Kind("mend-projector")
public final class MendProjector extends Building implements Checkpointable{
    public float heat, charge, phaseHeat, smoothEfficiency;

    @Derived("the block's parameters, read on first use")
    private Params params;

    /** The block's fields, as {@code MendProjector} declares them. */
    record Params(float reload, float range, float healPercent, float phaseBoost, float phaseRangeBoost, float useTime, int timerUse){
        static Params of(BlockDef block){
            if(!block.baked()) return new Params(250f, 60f, 12f, 12f, 50f, 400f, 0);
            var f = block.fields();
            return new Params(f.f("reload"), f.f("range"), f.f("healPercent"), f.f("phaseBoost"), f.f("phaseRangeBoost"),
                f.f("useTime"), f.i("timerUse"));
        }
    }

    Params params(){
        if(params == null) params = Params.of(block());
        return params;
    }

    /** {@code charge = Mathf.random(reload)}: the field initializer's draw. */
    @Override
    protected void constructed(){
        charge = Mathf.random(world().rand(), params().reload);
    }

    @Override
    public void updateTile(){
        Params p = params();
        boolean canHeal = !checkSuppression();
        smoothEfficiency = Mathf.lerpDelta(smoothEfficiency, efficiency, 0.08f);
        heat = Mathf.lerpDelta(heat, efficiency > 0 && canHeal ? 1f : 0f, 0.08f);
        charge += heat * delta();
        phaseHeat = Mathf.lerpDelta(phaseHeat, optionalEfficiency, 0.1f);
        if(optionalEfficiency > 0 && timer(p.timerUse, p.useTime / timeScale) && canHeal){
            consume();
        }
        if(charge >= p.reload && canHeal){
            float realRange = p.range + phaseHeat * p.phaseRangeBoost;
            charge = 0f;
            float percent = p.healPercent + phaseHeat * p.phaseBoost;
            boolean any = TeamBlocks.eachBlock(world(), team, x(), y(), realRange, b -> b.damaged() && !b.isHealSuppressed(), other -> {
                other.heal(other.maxHealth * percent / 100f * efficiency);
                other.recentlyHealed();
            });
            // mendSound.at(this, 1f + Mathf.range(0.1f), mendSoundVolume)
            if(any) Mathf.range(world().rand(), 0.1f);
        }
    }

    @Override
    public double sense(LAccess sensor){
        if(sensor == LAccess.progress) return Mathf.clamp(charge / params().reload);
        return super.sense(sensor);
    }

    @Override
    public void write(CheckpointOut out){
        out.f("heat", heat).f("charge", charge).f("phaseHeat", phaseHeat).f("smoothEfficiency", smoothEfficiency);
    }

    @Override
    public void read(CheckpointIn in){
        heat = in.f("heat");
        charge = in.f("charge");
        phaseHeat = in.f("phaseHeat");
        smoothEfficiency = in.f("smoothEfficiency");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (MendProjector)into;
        out.heat = heat;
        out.charge = charge;
        out.phaseHeat = phaseHeat;
        out.smoothEfficiency = smoothEfficiency;
        out.params = params;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.f(heat).f(charge).f(phaseHeat).f(smoothEfficiency);
    }
}
