package com.xpdustry.shard.sim.blocks.production;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code Pump.PumpBuild} (Pump.java): the mechanical, rotary, impulse and reinforced pumps. When its
 * proximity changes it adds up the liquid floors under it ({@link #amount}, each floor's {@code liquidMultiplier});
 * while it works it fills itself with that floor's liquid at {@code pumpAmount} per tile and dumps it. A pump that
 * takes items consumes them every {@code consumeTime} ticks (no vanilla pump does).
 *
 */
@Kind("pump")
public sealed class Pump extends Building implements Checkpointable permits SolidPump{
    public float warmup, totalProgress, consTimer, amount;
    /** The liquid it pumps, or -1. */
    public int liquidDrop = -1;
    @Derived("the block's parameters, read on first use")
    private Params params;

    /** {@code Pump}'s fields a server reads. */
    record Params(float pumpAmount, float consumeTime, float warmupSpeed){
        static Params of(BlockDef block){
            if(!block.baked()) return new Params(0.2f, 300f, 0.019f);
            var fields = block.fields();
            return new Params(fields.f("pumpAmount"), fields.f("consumeTime"), fields.f("warmupSpeed"));
        }
    }

    final Params pump(){
        if(params == null) params = Params.of(block());
        return params;
    }

    @Override
    public double sense(LAccess sensor){
        if(sensor == LAccess.efficiency) return shouldConsume() ? efficiency : 0f;
        if(sensor == LAccess.totalLiquids) return liquidDrop == -1 ? 0f : liquids.get(liquidDrop);
        return super.sense(sensor);
    }

    @Override
    public void onProximityUpdate(){
        super.onProximityUpdate();

        amount = 0f;
        liquidDrop = -1;

        TileGrid tiles = world().tiles();
        BlockTable table = tiles.table();
        Footprint.each(tiles, tileX(), tileY(), block().size(), pos -> {
            int floor = tiles.floor(pos);
            if(table.liquidDrop(floor) != BlockTable.NONE){
                liquidDrop = table.liquidDrop(floor);
                amount += table.liquidMultiplier(floor);
            }
        });
    }

    @Override
    public boolean shouldConsume(){
        return liquidDrop != -1 && liquids.get(liquidDrop) < info().liquidCapacity - 0.01f && enabled;
    }

    @Override
    public void updateTile(){
        Params params = pump();
        if(efficiency > 0 && liquidDrop != -1){
            float maxPump = Math.min(info().liquidCapacity - liquids.get(liquidDrop), amount * params.pumpAmount() * edelta());
            liquids.add(liquidDrop, maxPump);

            // does nothing for most pumps, as those do not require items
            if((consTimer += delta()) >= params.consumeTime()){
                consume();
                // vanilla quirk kept: the timer keeps its remainder modulo 1, not modulo consumeTime
                consTimer %= 1f;
            }

            warmup = Mathf.approachDelta(warmup, maxPump > 0.001f ? 1f : 0f, params.warmupSpeed());
        }else{
            warmup = Mathf.approachDelta(warmup, 0f, params.warmupSpeed());
        }

        totalProgress += warmup;

        if(liquidDrop != -1) dumpLiquid(liquidDrop);
    }

    @Override
    public void write(CheckpointOut out){
        out.f("warmup", warmup).f("totalProgress", totalProgress).f("consTimer", consTimer).f("amount", amount).i("liquidDrop", liquidDrop);
    }

    @Override
    public void read(CheckpointIn in){
        warmup = in.f("warmup");
        totalProgress = in.f("totalProgress");
        consTimer = in.f("consTimer");
        amount = in.f("amount");
        liquidDrop = in.i("liquidDrop");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (Pump)into;
        out.warmup = warmup;
        out.totalProgress = totalProgress;
        out.consTimer = consTimer;
        out.amount = amount;
        out.liquidDrop = liquidDrop;
        out.params = params;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.f(warmup).f(totalProgress).f(consTimer).f(amount).i(liquidDrop);
    }

    /** {@code pickedUp()}: a payload unit lifts it, and the liquid it pumps goes. */
    @Override
    public void pickedUp(){
        amount = 0f;
    }
}
