package com.xpdustry.shard.sim.blocks.power;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.blocks.heat.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code NuclearReactor.NuclearReactorBuild}: the thorium reactor. Its output is its fuel's fullness. Fuel
 * heats it, faster the fuller it is, and burns faster the hotter it runs; without fuel it cools slowly by itself.
 * Whatever liquid it holds cools it at {@code coolantPower} per unit, used up as it cools. At full heat it dies, and
 * its explosion ({@link Generator#onExplosion()}) needs five fuel or half heat.
 *
 * <p>Saved: {@link #heat}. The smoke effect's chance and position draw from the world's random. {@link #flash} and
 * {@link #smoothLight} only move while it is drawn, so they stay 0 on a server.
 */
@Kind("nuclear-reactor")
public final class NuclearReactor extends Generator implements HeatBlock{
    public float heat, heatLastFrame, heatProgress, flash, smoothLight;
    @Derived("the block's reactor parameters, read on first use")
    private Params params;

    record Params(int fuelItem, int timerFuel, float itemDuration, float heating, float heatOutput, float heatWarmupRate,
        float heatConsumeRate, float ambientCooldownTime, float smokeThreshold, float coolantPower){
        static Params of(Building building){
            BlockDef block = building.block();
            ContentEpoch content = building.world().content();
            if(!block.baked()) return new Params(content.id(ContentType.ITEM, "thorium"), 1, 120f, 0.01f, 8f, 1f, 10f, 60f * 20f, 0.3f, 0.5f);
            var fields = block.fields();
            return new Params(content.id(ContentType.ITEM, fields.s("fuelItem")), fields.i("timerFuel"), fields.f("itemDuration"),
                fields.f("heating"), fields.f("heatOutput"), fields.f("heatWarmupRate"), fields.f("heatConsumeRate"),
                fields.f("ambientCooldownTime"), fields.f("smokeThreshold"), fields.f("coolantPower"));
        }
    }

    private Params own(){
        if(params == null) params = Params.of(this);
        return params;
    }

    @Override
    public void updateTile(){
        Params params = own();
        int fuel = items.get(params.fuelItem());
        float fullness = (float)fuel / info().itemCapacity;
        productionEfficiency = fullness;

        if(fuel > 0 && enabled){
            heat += heatLastFrame = fullness * params.heating() * Math.min(delta(), 4f);
            if(timer(params.timerFuel(), params.itemDuration() / (timeScale + (heat > heatLastFrame ? 1f * heat * params.heatConsumeRate() : 0f)))){
                consume();
            }
        }else{
            productionEfficiency = 0f;
            heat = Math.max(0f, heat - 1f / params.ambientCooldownTime());
        }

        if(heat > 0){
            float maxUsed = Math.min(liquids.currentAmount(), heat / params.coolantPower());
            heat -= maxUsed * params.coolantPower();
            liquids.remove(liquids.current(), maxUsed);
        }

        if(heat > params.smokeThreshold()){
            // ranges from 1.0 to 2.0
            float smoke = 1.0f + (heat - params.smokeThreshold()) / (1f - params.smokeThreshold());
            Rand rand = world().rand();
            if(Mathf.chance(rand, smoke / 20.0 * delta())){
                // the smoke effect's position
                Mathf.range(rand, block().size() * Footprint.TILE_SIZE / 2f);
                Mathf.range(rand, block().size() * Footprint.TILE_SIZE / 2f);
            }
        }

        heat = Mathf.clamp(heat);
        heatProgress = params.heatOutput() > 0f ? Mathf.approachDelta(heatProgress,
            heat * params.heatOutput() * ((enabled && productionEfficiency > 0) ? 1f : 0f), params.heatWarmupRate() * delta()) : 0f;

        if(heat >= 0.999f) kill();
    }

    /**
     * {@code heat()} as a {@link HeatBlock} (NuclearReactor.java:159): what it gives heat consumers is
     * {@link #heatProgress}, not its own {@link #heat}.
     */
    @Override
    public float heat(){
        return heatProgress;
    }

    /** {@code heatFrac()}: the heat it gives heat consumers, as a fraction of {@code heatOutput}. */
    @Override
    public float heatFrac(){
        return heatProgress / own().heatOutput();
    }

    /** {@code sensor @heat}: the reactor's heat (NuclearReactor.java:164). */
    @Override
    public double sense(LAccess sensor){
        if(sensor == LAccess.heat) return heat;
        return super.sense(sensor);
    }

    @Override
    public boolean shouldExplode(){
        return super.shouldExplode() && (items.get(own().fuelItem()) >= 5 || heat >= 0.5f);
    }

    @Override
    public void write(CheckpointOut out){
        super.write(out);
        out.f("heat", heat).f("heatLastFrame", heatLastFrame).f("heatProgress", heatProgress).f("flash", flash)
            .f("smoothLight", smoothLight);
    }

    @Override
    public void read(CheckpointIn in){
        super.read(in);
        heat = in.f("heat");
        heatLastFrame = in.f("heatLastFrame");
        heatProgress = in.f("heatProgress");
        flash = in.f("flash");
        smoothLight = in.f("smoothLight");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (NuclearReactor)into;
        out.heat = heat;
        out.heatLastFrame = heatLastFrame;
        out.heatProgress = heatProgress;
        out.flash = flash;
        out.smoothLight = smoothLight;
        out.params = params;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.f(heat).f(heatLastFrame).f(heatProgress).f(flash).f(smoothLight);
    }
}
