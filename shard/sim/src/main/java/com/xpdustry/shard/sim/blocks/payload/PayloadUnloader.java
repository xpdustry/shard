package com.xpdustry.shard.sim.blocks.payload;

import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code PayloadUnloader.PayloadUnloaderBuild} (PayloadUnloader.java:41): a {@link PayloadLoaderBuild} run
 * backwards. It takes nothing from its neighbours; it empties the carried building's items (lowest id first,
 * {@code itemsLoaded} every {@code loadTime / efficiency} ticks, up to its capacity), its liquid ({@code liquidsLoaded} a
 * tick) and its battery ({@code maxPowerUnload} a tick, which it produces into its own graph the next power update),
 * dumps liquid and items ({@code offloadSpeed} times a tick), and moves the building out once it is empty, or destroys
 * it at once if its block deconstructs instantly. Its power use is fixed.
 *
 * <p>State: {@code lastOutputPower}, the power it produces now. Not saved: it saves what the loader does.
 */
@Kind("payload-unloader")
public final class PayloadUnloader extends PayloadLoaderBuild{
    public float lastOutputPower;

    @Override
    public boolean acceptItem(Building source, int item){
        return false;
    }

    @Override
    public boolean acceptLiquid(Building source, int liquid){
        return false;
    }

    @Override
    public float getPowerProduction(){
        return lastOutputPower;
    }

    @Override
    public void updateTile(){
        if(payload != null){
            payload.update(null, this);
            if(payload.isDead()){
                payload = null;
            }
        }
        lastOutputPower = 0f;
        LoaderParams params = params();
        if(shouldExport()){
            // one-use, disposable block
            if(PayloadCosts.place(world(), payload.block().id()).instantDeconstruct){
                payload = null;
            }else{
                moveOutPayload();
            }
        }else if(moveInPayload()){
            Building build = payload.building();
            // unload items
            if(build.block().hasItems() && !full()){
                if(efficiency > 0.01f && timer(params.timerLoad(), params.loadTime() / efficiency)){
                    // load up items a set amount of times
                    for(int j = 0; j < params.itemsLoaded() && !full(); j++){
                        for(int i = 0; i < items.length(); i++){
                            if(build.items.get(i) > 0){
                                build.items.remove(i, 1);
                                items.add(i, 1);
                                break;
                            }
                        }
                    }
                }
            }
            // unload liquids
            if(build.block().hasLiquids() && build.liquids.currentAmount() >= 0.01f
                && (liquids.current() == build.liquids.current() || liquids.currentAmount() <= 0.2f)){
                int liq = build.liquids.current();
                float remaining = info().liquidCapacity - liquids.currentAmount();
                float flow = Math.min(Math.min(params.liquidsLoaded() * edelta(), remaining), build.liquids.currentAmount());
                liquids.add(liq, flow);
                build.liquids.remove(liq, flow);
            }
            if(hasBattery()){
                float cap = consPower(build).capacity;
                float total = build.power.status * cap;
                float unloaded = Math.min(params.maxPowerUnload() * edelta(), total);
                lastOutputPower = unloaded;
                build.power.status -= unloaded / cap;
            }
        }
        dumpLiquid(liquids.current());
        for(int i = 0; i < params.offloadSpeed(); i++){
            dumpAccumulate(-1);
        }
    }

    /** {@code full()}: its items are at capacity. */
    public boolean full(){
        return items.total() >= info().itemCapacity;
    }

    /** {@code shouldExport()}: the carried building is empty of everything it holds. */
    @Override
    public boolean shouldExport(){
        if(payload == null) return false;
        Building build = payload.building();
        return (!build.block().hasItems() || build.items.empty())
            && (!build.block().hasLiquids() || build.liquids.currentAmount() <= 0.011f)
            && (!hasBattery() || build.power.status <= 0.0000001f);
    }

    @Override
    protected void copyState(com.xpdustry.shard.sim.entity.Entity into, CopyContext context){
        super.copyState(into, context);
        ((PayloadUnloader)into).lastOutputPower = lastOutputPower;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.f(lastOutputPower);
    }

    @Override
    public void write(CheckpointOut out){
        super.write(out);
        out.f("lastOutputPower", lastOutputPower);
    }

    @Override
    public void read(CheckpointIn in){
        super.read(in);
        lastOutputPower = in.f("lastOutputPower");
    }
}
