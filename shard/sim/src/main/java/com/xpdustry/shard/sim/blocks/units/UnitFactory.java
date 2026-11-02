package com.xpdustry.shard.sim.blocks.units;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.schema.*;
import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.building.consume.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code UnitFactory.UnitFactoryBuild} (UnitFactory.java:225): the ground, air and naval factories and
 * Erekir's tank, ship and mech fabricators. It builds the unit of its current plan ({@link #currentPlan}) from the
 * plan's items ({@code ConsumeItemDynamic}, scaled by {@code unitCost}) and power, at {@code unitBuildSpeed}, then holds
 * it as its payload and pushes it out of its front: onto a payload block that takes it, or onto the ground once the
 * team's cap allows ({@link Units#canCreate}, in {@code UnitPayload.dump}). While it holds a unit it consumes nothing,
 * so a capped team's factory stops until one of its units dies.
 *
 * <p>Configs: a plan index ({@code Integer}), a unit type, a command ({@link UnitBuild#command}) and {@code null},
 * which clears the command. A block that isn't {@code configurable} (the fabricators) only ever builds its first plan.
 *
 * <p>Not ported: the drawing, which a server never runs (its {@code draw()} sets {@code payRotation}; a server's never
 * moves from the dumped direction), and the {@code UnitCreateEvent}'s campaign stats.
 */
@Kind("unit-factory")
public final class UnitFactory extends UnitBuild implements Checkpointable{
    static final Configs<UnitFactory> CONFIGS = Configs.of(UnitFactory.class)
        .on(Integer.class, UnitFactory::configurePlan)
        .on(ContentType.UNIT, UnitFactory::configureUnit)
        .on(ContentRef.Foreign.class, UnitFactory::commandConfigured)
        .clear(factory -> factory.command = -1);

    /** {@code currentPlan}: the plan being built, or -1. */
    public int currentPlan = -1;

    @Derived("the block's plans and capacities, read on first use")
    private Params params;

    /** One {@code UnitPlan}: the unit, its items and its time. */
    record Plan(int unit, Stacks requirements, float time){
        boolean requires(int item){
            for(int i = 0; i < requirements.size(); i++) if(requirements.id(i) == item) return true;
            return false;
        }
    }

    record Params(Plan[] plans, int[] capacities, boolean configurable){
        static Params of(BlockDef block, ContentEpoch content){
            if(!block.baked()) return new Params(new Plan[0], new int[content.items()], true);
            List<Struct> list = block.fields().structs("plans");
            var plans = new Plan[list.size()];
            for(int i = 0; i < plans.length; i++){
                Struct plan = list.get(i);
                Map<String, Object> requirements = plan.map("requirements");
                int[] ids = new int[requirements.size()];
                float[] amounts = new float[requirements.size()];
                int at = 0;
                for(var entry : requirements.entrySet()){
                    ids[at] = content.id(ContentType.ITEM, entry.getKey());
                    amounts[at++] = ((Number)entry.getValue()).floatValue();
                }
                plans[i] = new Plan(content.id(ContentType.UNIT, plan.s("unit")), Stacks.of(ids, amounts), plan.f("time"));
            }
            return new Params(plans, readCapacities(block, content.items()), block.configurable());
        }
    }

    Params params(){
        if(params == null) params = Params.of(block(), world().content());
        return params;
    }

    @Override
    protected Configs<?> configs(){
        return CONFIGS;
    }

    /** {@code config(Integer.class, …)}: a plan by index; out of range is none. A changed command its unit lacks goes. */
    private void configurePlan(Integer i){
        Params p = params();
        if(!p.configurable) return;
        if(currentPlan == i) return;
        currentPlan = i < 0 || i >= p.plans.length ? -1 : i;
        progress = 0;
        int unit = unit();
        if(command != -1 && (unit == -1 || !hasCommand(unit, command))){
            command = -1;
        }
    }

    /** {@code config(UnitType.class, …)}: the plan that builds that unit, or none. */
    private void configureUnit(int type){
        Params p = params();
        if(!p.configurable) return;
        int next = -1;
        for(int i = 0; i < p.plans.length; i++){
            if(p.plans[i].unit == type){
                next = i;
                break;
            }
        }
        if(currentPlan == next) return;
        currentPlan = next;
        progress = 0;
        if(command != -1 && !hasCommand(type, command)){
            command = -1;
        }
    }

    /** {@code fraction()}: how far the current unit is. */
    public float fraction(){
        return currentPlan == -1 ? 0 : progress / params().plans[currentPlan].time;
    }

    /** {@code unit()}: the current plan's unit type, or -1. */
    public int unit(){
        return currentPlan == -1 ? -1 : params().plans[currentPlan].unit;
    }

    /** {@code created()}: a new factory starts on its first plan whose unit is unlocked. */
    @Override
    public void created(){
        if(currentPlan == -1){
            Plan[] plans = params().plans;
            for(int i = 0; i < plans.length; i++){
                if(unlockedNow(plans[i].unit)){
                    currentPlan = i;
                    break;
                }
            }
        }
    }

    @Override
    public Object config(){
        return currentPlan;
    }

    @Override
    public void updateTile(){
        Params p = params();
        if(!p.configurable){
            currentPlan = 0;
        }
        if(currentPlan < 0 || currentPlan >= p.plans.length){
            currentPlan = -1;
        }
        if(efficiency > 0 && currentPlan != -1){
            time += edelta() * speedScl * unitBuildSpeed();
            progress += edelta() * unitBuildSpeed();
            speedScl = Mathf.lerpDelta(speedScl, 1f, 0.05f);
        }else{
            speedScl = Mathf.lerpDelta(speedScl, 0f, 0.05f);
        }
        moveOutPayload();
        if(currentPlan != -1 && payload == null){
            Plan plan = p.plans[currentPlan];
            // make sure to reset plan when the unit got banned after placement
            if(banned(plan.unit)){
                currentPlan = -1;
                return;
            }
            if(progress >= plan.time){
                progress %= 1f;
                Unit unit = Unit.create(world(), world().content().unit(plan.unit), team);
                commandUnit(unit);
                createSound();
                payload = new Payload.UnitPayload(unit);
                payVector.setZero();
                consume();
                unitCreated(unit);
            }
            progress = Mathf.clamp(progress, 0, plan.time);
        }else{
            progress = 0f;
        }
    }

    @Override
    public boolean shouldConsume(){
        if(currentPlan == -1) return false;
        return enabled && payload == null && activateUnitFactories();
    }

    /** {@code ConsumeItemDynamic}'s {@code hook:unit-factory.items}: the current plan's items. */
    @Override
    public Stacks consumedItems(){
        Plan[] plans = params().plans;
        return currentPlan != -1 ? plans[Math.min(currentPlan, plans.length - 1)].requirements : Stacks.EMPTY;
    }

    /** {@code initCapacities}' consumer multiplier ({@code hook:unit-factory.multiplier}): the team's unit cost. */
    @Override
    public float consumeMultiplier(Consume consume){
        return unitCost();
    }

    @Override
    public int getMaximumAccepted(int item){
        return maximumAccepted(params().capacities, item);
    }

    @Override
    public boolean acceptItem(Building source, int item){
        Plan[] plans = params().plans;
        // plans.get(currentPlan) would throw on a plan a save left out of range; nothing is accepted then
        return currentPlan != -1 && currentPlan < plans.length && items.get(item) < getMaximumAccepted(item) && plans[currentPlan].requires(item);
    }

    /** {@code acceptPayload}: a factory takes no payloads. */
    @Override
    public boolean acceptPayload(Building source, Payload payload){
        return false;
    }

    @Override
    public double sense(LAccess sensor){
        if(sensor == LAccess.progress) return Mathf.clamp(fraction());
        if(sensor == LAccess.itemCapacity) return scaledItemCapacity();
        return super.sense(sensor);
    }

    @Override
    public Object senseObject(LAccess sensor){
        if(sensor == LAccess.config) return currentPlan == -1 ? null : new ContentRef(ContentType.UNIT, params().plans[currentPlan].unit);
        return super.senseObject(sensor);
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (UnitFactory)into;
        out.currentPlan = currentPlan;
        out.params = params;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.i(currentPlan);
    }

    @Override
    public void write(CheckpointOut out){
        super.write(out);
        out.i("currentPlan", currentPlan);
    }

    @Override
    public void read(CheckpointIn in){
        super.read(in);
        currentPlan = in.i("currentPlan");
    }
}
