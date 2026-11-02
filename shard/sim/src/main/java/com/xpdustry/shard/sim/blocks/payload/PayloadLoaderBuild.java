package com.xpdustry.shard.sim.blocks.payload;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.building.consume.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code PayloadLoader.PayloadLoaderBuild} (PayloadLoader.java:96), the base of the payload loader and
 * unloader ({@link PayloadLoader}, {@link PayloadUnloader}). It takes a carried building that holds items (unloadable,
 * at least 10, at most {@code maxBlockSize} tiles), liquids (at least 10) or power (a battery), pulls it to its centre,
 * fills it, and moves it out of its front once it is {@code exporting}: when the building refused an item or a liquid,
 * an item stack of a {@code separateItemCapacity} block is full, its tank is full, or its battery is.
 *
 * <p>Items go in {@code itemsLoaded} at a time every {@code loadTime / efficiency} ticks (timer {@code timerLoad}),
 * lowest item id first; liquid at {@code liquidsLoaded} a tick; power from what its dynamic power use draws beyond its
 * base, {@code maxPowerConsumption} while it charges a battery. It consumes whenever it holds a payload, enabled or
 * not, as vanilla's does.
 *
 * <p>State: {@code exporting}, saved at revision 1 after the payload block's body.
 */
public abstract class PayloadLoaderBuild extends PayloadBuild<Payload.BuildPayload>{
    private static final KindKey LIQUID_VOID = KindKey.of("liquid-void");

    public boolean exporting;

    @Derived("the block's parameters, read on first use")
    LoaderParams params;

    /** The block's loader fields. {@code basePowerUse} is its power consumer's usage before {@code init()} made it dynamic. */
    record LoaderParams(int timerLoad, float loadTime, int itemsLoaded, float liquidsLoaded, int maxBlockSize,
                        float maxPowerConsumption, float basePowerUse, int offloadSpeed, float maxPowerUnload){
        static LoaderParams of(BlockDef block){
            if(!block.baked()) return new LoaderParams(1, 2f, 8, 40f, 3, 40f, 0f, 4, 80f);
            var f = block.fields();
            boolean unloader = f.has("offloadSpeed");
            return new LoaderParams(f.i("timerLoad"), f.f("loadTime"), f.i("itemsLoaded"), f.f("liquidsLoaded"), f.i("maxBlockSize"),
                f.f("maxPowerConsumption"), f.f("basePowerUse"), unloader ? f.i("offloadSpeed") : 4, unloader ? f.f("maxPowerUnload") : 80f);
        }
    }

    LoaderParams params(){
        if(params == null) params = LoaderParams.of(block());
        return params;
    }

    /** The carried building's block's power consumer, or null. */
    static ConsumePower consPower(Building building){
        return building.info().consumers.power();
    }

    @Override
    public boolean acceptPayload(Building source, Payload payload){
        int max = params().maxBlockSize();
        if(!super.acceptPayload(source, payload) || !payload.fits(max) || !(payload instanceof Payload.BuildPayload build)) return false;
        BlockDef block = build.block();
        BlockInfo info = build.building().info();
        ConsumePower power = consPower(build.building());
        return (block.hasItems() && info.unloadable && info.itemCapacity >= 10 && block.size() <= max)
            || (block.hasLiquids() && info.liquidCapacity >= 10f)
            || (power != null && power.buffered);
    }

    @Override
    public void handlePayload(Building source, Payload payload){
        super.handlePayload(source, payload);
        exporting = false;
    }

    @Override
    public boolean acceptItem(Building source, int item){
        return items.total() < info().itemCapacity && !(source instanceof PayloadUnloader);
    }

    @Override
    public boolean acceptLiquid(Building source, int liquid){
        return (liquids.current() == liquid || liquids.currentAmount() < 0.2f) && !(source instanceof PayloadUnloader);
    }

    @Override
    public void updateTile(){
        super.updateTile();
        if(shouldExport()){
            moveOutPayload();
        }else if(moveInPayload()){
            LoaderParams params = params();
            Building build = payload.building();
            BlockDef block = build.block();
            BlockInfo info = build.info();
            // load up items
            if(block.hasItems() && items.any()){
                boolean acceptedAny = true;
                if(efficiency > 0.01f && timer(params.timerLoad(), params.loadTime() / efficiency)){
                    acceptedAny = false;
                    // load up items a set amount of times
                    for(int j = 0; j < params.itemsLoaded() && items.any(); j++){
                        for(int i = 0; i < items.length(); i++){
                            if(items.get(i) > 0){
                                if(build.acceptItem(build, i)){
                                    build.handleItem(build, i);
                                    items.remove(i, 1);
                                    acceptedAny = true;
                                    break;
                                }else if(block.separateItemCapacity() || info.consumesItem(i)){
                                    exporting = true;
                                    break;
                                }
                            }
                        }
                    }
                }
                if(!acceptedAny){
                    exporting = true;
                }
            }
            // load up liquids
            if(block.hasLiquids() && liquids.currentAmount() >= 0.001f){
                int liq = liquids.current();
                float total = liquids.currentAmount();
                float flow = Math.min(Math.min(params.liquidsLoaded() * edelta(), info.liquidCapacity - build.liquids.get(liq)), total);
                if(build.acceptLiquid(build, liq)){
                    if(!block.isA(LIQUID_VOID)){
                        build.liquids.add(liq, flow);
                    }
                    liquids.remove(liq, flow);
                }else{
                    exporting = true;
                }
            }
            // load up power
            if(hasBattery()){
                // base power input that in raw units
                float powerInput = power.status * (params.basePowerUse() + params.maxPowerConsumption());
                // how much is actually usable
                float availableInput = Math.max(powerInput - params.basePowerUse(), 0f);
                // charge the battery
                float cap = consPower(build).capacity;
                build.power.status += availableInput / cap * edelta();
                // export if full
                if(build.power.status >= 1f){
                    exporting = true;
                    build.power.status = Mathf.clamp(build.power.status);
                }
            }
        }
    }

    /** {@code fraction()}: how full the carried building is, by items, else liquid, else power. */
    public float fraction(){
        if(payload == null) return 0f;
        Building build = payload.building();
        if(build.items != null) return build.items.total() / (float)build.info().itemCapacity;
        if(build.liquids != null) return build.liquids.currentAmount() / build.info().liquidCapacity;
        return hasBattery() ? build.power.status : 0f;
    }

    /** {@code shouldExport()}. */
    public boolean shouldExport(){
        if(payload == null) return false;
        if(exporting) return true;
        Building build = payload.building();
        BlockDef block = build.block();
        BlockInfo info = build.info();
        if(block.hasLiquids() && liquids.currentAmount() >= 0.1f && build.liquids.currentAmount() >= info.liquidCapacity - 0.001f) return true;
        if(block.hasItems() && items.any() && block.separateItemCapacity()){
            for(int i = 0; i < items.length(); i++){
                if(build.items.get(i) >= info.itemCapacity && items.has(i)) return true;
            }
        }
        return hasBattery() && build.power.status >= 0.999999999f;
    }

    /** {@code hasBattery()}: it holds a building with a buffered power consumer. */
    public boolean hasBattery(){
        if(payload == null) return false;
        ConsumePower power = consPower(payload.building());
        return power != null && power.buffered;
    }

    @Override
    public boolean shouldConsume(){
        return payload != null;
    }

    @Override
    protected void copyState(com.xpdustry.shard.sim.entity.Entity into, CopyContext context){
        super.copyState(into, context);
        ((PayloadLoaderBuild)into).exporting = exporting;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.bool(exporting);
    }

    @Override
    public void write(CheckpointOut out){
        super.write(out);
        out.bool("exporting", exporting);
    }

    @Override
    public void read(CheckpointIn in){
        super.read(in);
        exporting = in.bool("exporting");
    }
}
