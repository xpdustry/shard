package com.xpdustry.shard.sim.blocks.sandbox;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code LandingPad.LandingPadBuild} (LandingPad.java) as a server hosts it off the campaign, where every
 * pad is "fake" ({@code isFake()}): an item source for maps. Configured with an item, an idle pad with
 * {@code consumeLiquidAmount} of water and no items calls a pod down ({@link SandboxEvent.PadLanded}, vanilla's
 * {@code Call.landingPadLanded}); over {@code arrivalDuration} ticks the pod uses that water up, then fills the pad
 * with {@code itemCapacity} of the item, which it dumps; after {@code cooldownTime} it calls the next. It takes no
 * items. Only some may configure it ({@code accessible()}): editors, world-processor maps, sandboxes, and teams other
 * than the default one outside PvP.
 *
 * <p>Its efficiency is its water consumer's own (LandingPad.java:96): 1 with enough water, else 0, not the liquid
 * consumer's fraction. Its construction draws {@link #priority} (LandingPad.java:139), which only the campaign's
 * landing queue reads.
 *
 * <p>Not ported: the campaign's import timers and landing queue ({@code updateTimers}), and everything else that
 * {@code state.isCampaign()} gates.
 */
@Kind("landing-pad")
public final class LandingPad extends Building implements Checkpointable{
    static final Configs<LandingPad> CONFIGS = Configs.of(LandingPad.class)
        .on(ContentType.ITEM, LandingPad::configure)
        .clear(build -> {
            if(build.accessible()) build.config = -1;
        });
    private static final List<String> PLANETS = List.of("sun", "erekir", "gier", "notva", "tantros", "serpulo", "verilus");

    /** The item it calls down, or -1. */
    public int config = -1;
    /** The campaign's landing order; drawn when the pad is made. */
    public int priority;
    public float cooldown;
    public float arrivingTimer;
    /** The item on its way down, or -1. */
    public int arriving = -1;
    public float liquidRemoved;
    @Derived("the block's landing parameters, read on first use")
    private Params params;

    private record Params(float arrivalDuration, float cooldownTime, float consumeLiquidAmount, int consumeLiquid, float coolingEffectChance){
    }

    /** The config handler (LandingPad.java:73): an item on the rules' planet, by those who may. */
    private static void configure(LandingPad build, int item){
        if(!build.accessible() || !build.onPlanet(item)) return;
        build.config = item;
    }

    /** {@code priority = Mathf.rand.nextInt()}. */
    @Override
    protected void constructed(){
        priority = world().rand().nextInt();
    }

    @Override
    public void updateConsumption(){
        // the water consumer's own efficiency; it takes nothing per tick (update(false))
        if(cheating()){
            potentialEfficiency = enabled ? 1f : 0f;
            efficiency = optionalEfficiency = potentialEfficiency;
            shouldConsumePower = true;
            return;
        }
        if(!enabled){
            potentialEfficiency = efficiency = optionalEfficiency = 0f;
            shouldConsumePower = false;
            return;
        }
        Params p = params();
        float result = liquids.get(p.consumeLiquid) >= p.consumeLiquidAmount ? 1f : 0f;
        shouldConsumePower = result > 0.0000001f;
        efficiency = optionalEfficiency = potentialEfficiency = result;
    }

    @Override
    public void updateTile(){
        Params p = params();
        if(arriving != -1){
            arrivingTimer += 1f / p.arrivalDuration;
            float toRemove = Math.min(p.consumeLiquidAmount / p.arrivalDuration * 1f, p.consumeLiquidAmount - liquidRemoved);
            liquidRemoved += toRemove;
            liquids.remove(p.consumeLiquid, toRemove);
            // the cooling effect's chance, drawn on a server too
            Mathf.chanceDelta(world().rand(), p.coolingEffectChance * Interp.pow5Out.apply(arrivingTimer));
            if(arrivingTimer >= 1f){
                // remove any leftovers to make sure it's precise
                liquids.remove(p.consumeLiquid, p.consumeLiquidAmount - liquidRemoved);
                items.set(arriving, info().itemCapacity);
                arriving = -1;
                arrivingTimer = 0f;
            }
        }
        if(items.total() > 0) dumpAccumulate(config == -1 || items.get(config) != items.total() ? -1 : config);
        if(arriving == -1){
            cooldown -= delta() / p.cooldownTime;
            cooldown = Mathf.clamp(cooldown);
        }
        // there is no queue for fake pads
        if(config != -1 && cooldown <= 0f && efficiency > 0f && items.total() == 0) landed();
    }

    /** {@code Call.landingPadLanded(tile)}: {@code handleLanding()} here, then the Call. */
    public void landed(){
        if(config != -1){
            cooldown = 1f;
            arriving = config;
            arrivingTimer = 0f;
            liquidRemoved = 0f;
        }
        world().publish(new SandboxEvent.PadLanded(world().ticks(), tile()));
    }

    /** {@code accessible()}: off the campaign, editors, world-processor maps, sandboxes, and non-default teams outside PvP. */
    public boolean accessible(){
        Rules rules = rules();
        return rules.editor || rules.allowEditWorldProcessors || rules.sector != null || rules.infiniteResources
            || (team != rules.defaultTeam && !rules.pvp && team != VanillaIds.DERELICT);
    }

    /** {@code item.isOnPlanet(state.getPlanet())}: no planet, the sun, an item shown everywhere, or one shown there. */
    private boolean onPlanet(int item){
        Rules rules = rules();
        int planet = rules.planet;
        if(rules.sector != null){
            int dash = rules.sector.lastIndexOf('-');
            planet = PLANETS.indexOf(dash < 0 ? rules.sector : rules.sector.substring(0, dash));
        }
        List<Object> shown = world().content().item(item).fields().list("shownPlanets");
        if(planet < 0 || planet == 0 || shown == null || shown.isEmpty()) return true;
        return shown.contains(PLANETS.get(planet));
    }

    @Override
    public boolean acceptItem(Building source, int item){
        return false;
    }

    @Override
    protected Configs<?> configs(){
        return CONFIGS;
    }

    @Override
    public Object config(){
        return config == -1 ? null : ContentRef.item(config);
    }

    private Params params(){
        if(params == null){
            BlockDef block = block();
            var f = block.fields();
            params = block.baked()
                ? new Params(f.f("arrivalDuration"), f.f("cooldownTime"), f.f("consumeLiquidAmount"),
                    world().content().id(ContentType.LIQUID, f.s("consumeLiquid")), f.f("coolingEffectChance"))
                : new Params(150f, 150f, 100f, 0, 0.2f);
        }
        return params;
    }

    @Override
    public void write(CheckpointOut out){
        out.i("config", config).i("priority", priority).f("cooldown", cooldown).f("arrivingTimer", arrivingTimer).i("arriving", arriving)
            .f("liquidRemoved", liquidRemoved);
    }

    @Override
    public void read(CheckpointIn in){
        config = in.i("config");
        priority = in.i("priority");
        cooldown = in.f("cooldown");
        arrivingTimer = in.f("arrivingTimer");
        arriving = in.i("arriving");
        liquidRemoved = in.f("liquidRemoved");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (LandingPad)into;
        out.config = config;
        out.priority = priority;
        out.cooldown = cooldown;
        out.arrivingTimer = arrivingTimer;
        out.arriving = arriving;
        out.liquidRemoved = liquidRemoved;
        out.params = params;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.i(config).i(priority).f(cooldown).f(arrivingTimer).i(arriving).f(liquidRemoved);
    }
}
