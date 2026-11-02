package com.xpdustry.shard.sim.blocks.units;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.building.consume.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code Reconstructor.ReconstructorBuild} (Reconstructor.java:168): the additive, multiplicative,
 * exponential and tetrative reconstructors and Erekir's refabricators. It takes a unit it can upgrade from any side but
 * its front, pulls it to its centre and, fed and powered, replaces it after {@code constructTime} (at
 * {@code unitBuildSpeed}) with a new unit of the upgraded type, which it pushes out of its front like a factory. A unit
 * it can't upgrade passes straight through.
 *
 * <p>Configs: a command ({@link UnitBuild#command}) and {@code null}, which clears it.
 *
 * <p>State besides {@link UnitBuild}'s: {@link #constructing}, cached at the start of each update and read by
 * {@link #shouldConsume()} in the next tick's consumption, before the update. Vanilla doesn't save it, so a loaded
 * reconstructor consumes nothing in its first tick.
 *
 * <p>Not ported: the drawing, effects (a headless server draws none: {@code Effect.shake}, {@code Fx.producesmoke}),
 * and {@code acceptPayload}'s overlay and {@code Trigger.cannotUpgrade}, which only a client shows.
 */
@Kind("reconstructor")
public final class Reconstructor extends UnitBuild implements Checkpointable{
    static final Configs<Reconstructor> CONFIGS = Configs.of(Reconstructor.class)
        .on(ContentRef.Foreign.class, Reconstructor::commandConfigured)
        .clear(reconstructor -> reconstructor.command = -1);

    /** {@code constructing}: whether the payload was being upgraded at the start of the last update. */
    public boolean constructing;

    @Derived("the block's upgrades and capacities, read on first use")
    private Params params;

    /** {@code upgrades} as type ids, from → to, in the block's order; {@code constructTime}; {@code capacities}. */
    record Params(int[] from, int[] to, float constructTime, int[] capacities){
        static Params of(BlockDef block, ContentEpoch content){
            if(!block.baked()) return new Params(new int[0], new int[0], 60f * 2f, new int[content.items()]);
            List<Object> upgrades = block.fields().list("upgrades");
            int[] from = new int[upgrades.size()], to = new int[upgrades.size()];
            for(int i = 0; i < from.length; i++){
                List<?> pair = (List<?>)upgrades.get(i);
                from[i] = content.id(ContentType.UNIT, (String)pair.get(0));
                to[i] = content.id(ContentType.UNIT, (String)pair.get(1));
            }
            return new Params(from, to, block.fields().f("constructTime"), readCapacities(block, content.items()));
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

    /** {@code fraction()}. */
    public float fraction(){
        return progress / params().constructTime;
    }

    /** {@code upgrade(type)}: what the block turns {@code type} into, or -1 ({@code upgrades.find}, the first match). */
    public int upgrade(int type){
        Params p = params();
        for(int i = 0; i < p.from.length; i++) if(p.from[i] == type) return p.to[i];
        return -1;
    }

    /** {@code hasUpgrade(type)}: an upgrade the team may build, for a type that isn't banned. */
    public boolean hasUpgrade(int type){
        int t = upgrade(type);
        return t != -1 && (unlockedNow(t) || teamIsAI()) && !banned(type);
    }

    /** {@code constructing()}: it holds a unit it upgrades. */
    public boolean isConstructing(){
        return payload != null && hasUpgrade(payload.unit().type().id());
    }

    /** {@code unit()}: what the held unit becomes, if the team may build it; -1 otherwise. */
    public int unit(){
        if(payload == null) return -1;
        int t = upgrade(payload.unit().type().id());
        return t != -1 && (unlockedNow(t) || teamIsAI()) ? t : -1;
    }

    @Override
    public boolean acceptUnitPayload(Unit unit){
        return hasUpgrade(unit.type().id()) && !banned(upgrade(unit.type().id()));
    }

    /** {@code acceptPayload}: an empty, enabled block takes a unit it may upgrade, from any side but its front. */
    @Override
    public boolean acceptPayload(Building source, Payload payload){
        if(!(this.payload == null && (this.enabled || source == this) && relativeTo(source) != rotation
            && payload instanceof Payload.UnitPayload pay)){
            return false;
        }
        int upgrade = upgrade(pay.unit().type().id());
        return upgrade != -1 && (teamIsAI() || unlockedNow(upgrade)) && !banned(upgrade);
    }

    @Override
    public void overwrote(List<Building> previous){
        // vanilla calls it only with something overwritten
        if(previous.isEmpty()) return;
        Building first = previous.getFirst();
        if(first.block() == block()) items.add(first.items);
    }

    @Override
    public Object config(){
        return commandConfig();
    }

    @Override
    public void updateTile(){
        // cache value to prevent repeated calls and multithreading issues
        constructing = isConstructing();
        boolean valid = false;
        if(payload != null){
            // check if offloading
            if(!hasUpgrade(payload.unit().type().id())){
                moveOutPayload();
            }else if(moveInPayload()){ // update progress
                if(efficiency > 0){
                    valid = true;
                    progress += edelta() * unitBuildSpeed();
                }
                // upgrade the unit
                float constructTime = params().constructTime;
                if(progress >= constructTime){
                    Unit old = payload.unit();
                    Unit unit = Unit.create(world(), world().content().unit(upgrade(old.type().id())), old.team);
                    payload = new Payload.UnitPayload(unit);
                    commandUnit(unit);
                    createSound();
                    progress %= 1f;
                    consume();
                    unitCreated(unit);
                }
            }
        }
        speedScl = Mathf.lerpDelta(speedScl, valid ? 1f : 0f, 0.05f);
        time += edelta() * speedScl * unitBuildSpeed();
    }

    @Override
    public boolean shouldConsume(){
        return constructing && enabled && activateUnitFactories();
    }

    /** {@code initCapacities}' consumer multiplier ({@code hook:reconstructor.multiplier}): the team's unit cost. */
    @Override
    public float consumeMultiplier(Consume consume){
        return unitCost();
    }

    @Override
    public int getMaximumAccepted(int item){
        return maximumAccepted(params().capacities, item);
    }

    @Override
    public double sense(LAccess sensor){
        if(sensor == LAccess.progress) return Mathf.clamp(fraction());
        if(sensor == LAccess.itemCapacity) return scaledItemCapacity();
        return super.sense(sensor);
    }

    @Override
    public Object senseObject(LAccess sensor){
        if(sensor == LAccess.config){
            int unit = unit();
            return unit == -1 ? null : new ContentRef(ContentType.UNIT, unit);
        }
        return super.senseObject(sensor);
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (Reconstructor)into;
        out.constructing = constructing;
        out.params = params;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.bool(constructing);
    }

    @Override
    public void write(CheckpointOut out){
        super.write(out);
        out.bool("constructing", constructing);
    }

    @Override
    public void read(CheckpointIn in){
        super.read(in);
        constructing = in.bool("constructing");
    }
}
