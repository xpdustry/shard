package com.xpdustry.shard.sim.blocks.defense;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code OverdriveProjector.OverdriveBuild} (OverdriveProjector.java): the overdrive projector and dome.
 * Every {@code reload} ticks of charge it boosts the buildings of its team in range that can be overdriven to
 * {@link #realBoost()} for {@code reload + 1} ticks ({@link Building#applyBoost}, which raises their
 * {@link Building#timeScale}). The boost follows efficiency, and with {@code hasBoost} the optional item warms
 * {@link #phaseHeat}, which adds {@code speedBoostPhase} and {@code phaseRangeBoost}. Its items are consumed every
 * {@code useTime} ticks of its own {@link #delta()} while it runs. It can't be overdriven itself.
 *
 * <p>Saved: {@link #heat} and {@link #phaseHeat}; {@link #charge} restarts at a fresh random on load, as vanilla's
 * field initializer draws it ({@link #constructed()}), and {@link #useProgress} at 0.
 */
@Kind("overdrive-projector")
public final class OverdriveProjector extends Building implements Checkpointable{
    public float heat, charge, phaseHeat, smoothEfficiency, useProgress;

    @Derived("the block's parameters, read on first use")
    private Params params;

    /** The block's fields, as {@code OverdriveProjector} declares them. */
    record Params(float reload, float range, float speedBoost, float speedBoostPhase, float useTime, float phaseRangeBoost, boolean hasBoost){
        static Params of(BlockDef block){
            if(!block.baked()) return new Params(60f, 80f, 1.5f, 0.75f, 400f, 20f, true);
            var f = block.fields();
            return new Params(f.f("reload"), f.f("range"), f.f("speedBoost"), f.f("speedBoostPhase"), f.f("useTime"),
                f.f("phaseRangeBoost"), f.b("hasBoost"));
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
        smoothEfficiency = Mathf.lerpDelta(smoothEfficiency, efficiency, 0.08f);
        heat = Mathf.lerpDelta(heat, efficiency > 0 ? 1f : 0f, 0.08f);
        // Time.delta, not delta(): the projector's own time scale doesn't speed its charge
        charge += heat;
        if(p.hasBoost) phaseHeat = Mathf.lerpDelta(phaseHeat, optionalEfficiency, 0.1f);
        if(charge >= p.reload){
            float realRange = p.range + phaseHeat * p.phaseRangeBoost;
            charge = 0f;
            TeamBlocks.eachBlock(world(), team, x(), y(), realRange, other -> other.info().canOverdrive,
                other -> other.applyBoost(realBoost(), p.reload + 1f));
        }
        if(efficiency > 0) useProgress += delta();
        if(useProgress >= p.useTime){
            consume();
            useProgress %= p.useTime;
        }
    }

    /** {@code realBoost()}: the time scale it gives. */
    public float realBoost(){
        Params p = params();
        return (p.speedBoost + phaseHeat * p.speedBoostPhase) * efficiency;
    }

    @Override
    public void write(CheckpointOut out){
        out.f("heat", heat).f("charge", charge).f("phaseHeat", phaseHeat).f("smoothEfficiency", smoothEfficiency)
            .f("useProgress", useProgress);
    }

    @Override
    public void read(CheckpointIn in){
        heat = in.f("heat");
        charge = in.f("charge");
        phaseHeat = in.f("phaseHeat");
        smoothEfficiency = in.f("smoothEfficiency");
        useProgress = in.f("useProgress");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (OverdriveProjector)into;
        out.heat = heat;
        out.charge = charge;
        out.phaseHeat = phaseHeat;
        out.smoothEfficiency = smoothEfficiency;
        out.useProgress = useProgress;
        out.params = params;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.f(heat).f(charge).f(phaseHeat).f(smoothEfficiency).f(useProgress);
    }
}
