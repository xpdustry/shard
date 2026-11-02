package com.xpdustry.shard.sim.blocks.distribution;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code Router.RouterBuild} (Router.java): the router and the distributor. It holds one item at a time,
 * waits {@code 1 / speed} of a tick per tick (scaled by {@link #delta()}) unless the target takes items instantly, and
 * hands it to the next neighbour that accepts it, rotating through the proximity separately for each item
 * ({@link #cycles}, vanilla's fix for issue 12471). It never gives an item straight back to the overflow gate it came
 * from.
 *
 * <p>Vanilla saves none of this ({@code RouterBuild} has no {@code write}): after a load {@link #lastItem} is the
 * first item held and the rest starts over.
 *
 * <p>Not ported: a player controlling a router through its block unit ({@code ControlBlock}), which creates that unit
 * on first use. Until a router has one, vanilla's control branch never runs.
 */
@Kind("router")
public final class Router extends Building implements Checkpointable{
    private static final KindKey ROUTER = KindKey.of("router");

    /** Per item id: the proximity offset the next search for that item starts at. */
    public byte[] cycles = new byte[0];
    /** The item being routed, or -1. */
    public int lastItem = -1;
    /** The tile the item came from ({@code y * width + x}), or -1. */
    public int lastInput = -1;
    public float time;
    @Derived("the block's speed, read on first use")
    private float speed = Float.NaN;
    @Derived("the content's overflow-gate id, read on first use")
    private int overflowGate = -2;

    @Override
    public void created(){
        cycles = new byte[world().content().items()];
    }

    @Override
    public void updateTile(){
        if(lastItem == -1 && items.any()) lastItem = items.first();
        if(lastItem != -1){
            time += 1f / speed() * delta();
            Building target = getTileTarget(lastItem, lastInput, false);
            if(target != null && (time >= 1f || !(isRouter(target) || target.info().instantTransfer))){
                getTileTarget(lastItem, lastInput, true);
                target.handleItem(this, lastItem);
                items.remove(lastItem, 1);
                lastItem = -1;
            }
        }
    }

    @Override
    public int acceptStack(int item, int amount, int sourceTeam){
        return 0;
    }

    @Override
    public boolean acceptItem(Building source, int item){
        return team == source.team && lastItem == -1 && items.total() == 0;
    }

    @Override
    public void handleItem(Building source, int item){
        items.add(item, 1);
        lastItem = item;
        time = 0f;
        lastInput = source.tile();
    }

    @Override
    public int removeStack(int item, int amount){
        int result = super.removeStack(item, amount);
        if(result != 0 && item == lastItem) lastItem = -1;
        return result;
    }

    /**
     * {@code getTileTarget(item, from, set)}: the first neighbour from this item's offset that accepts it, skipping
     * the overflow gate it came from; {@code set} advances the offset once per neighbour tried.
     */
    public Building getTileTarget(int item, int from, boolean set){
        int counter = cycles[item];
        int size = proximitySize();
        for(int i = 0; i < size; i++){
            Building other = proximity((i + counter) % size);
            if(set) cycles[item] = (byte)((cycles[item] + 1) % size);
            if(from != -1 && other.tile() == from && world().tiles().block(from) == overflowGate()) continue;
            if(other.acceptItem(this, item)) return other;
        }
        return null;
    }

    private static boolean isRouter(Building building){
        return building instanceof Router || building.block().baked() && building.block().isA(ROUTER);
    }

    private float speed(){
        if(Float.isNaN(speed)) speed = block().baked() ? block().fields().f("speed") : 8f;
        return speed;
    }

    private int overflowGate(){
        if(overflowGate == -2) overflowGate = world().content().id(ContentType.BLOCK, "overflow-gate");
        return overflowGate;
    }

    @Override
    public void write(CheckpointOut out){
        out.bytes("cycles", cycles).i("lastItem", lastItem).i("lastInput", lastInput).f("time", time);
    }

    @Override
    public void read(CheckpointIn in){
        cycles = in.bytes("cycles");
        lastItem = in.i("lastItem");
        lastInput = in.i("lastInput");
        time = in.f("time");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (Router)into;
        out.cycles = cycles.clone();
        out.lastItem = lastItem;
        out.lastInput = lastInput;
        out.time = time;
        out.speed = speed;
        out.overflowGate = overflowGate;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.bytes(cycles).i(lastItem).i(lastInput).f(time);
    }
}
