package com.xpdustry.shard.sim.blocks.production;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code Drill.DrillBuild} (Drill.java): the mechanical, pneumatic, laser and blast drills. When its proximity
 * changes it counts the ore under its footprint and mines the dominant item ({@link #countOre}): the most common one
 * it may mine, low-priority items (sand) only when nothing else is there. It warms up towards its speed, which an
 * optional liquid boosts ({@code liquidBoostIntensity}), and offloads every whole item its progress makes, while it
 * has room; every {@code dumpTime / timeScale} ticks it also dumps one.
 *
 * <p>While drilling, the update effect's chance and position draw from the world's random every tick, as vanilla's
 * do on a server. The drill effect doesn't: vanilla plays it only where a renderer saw the building
 * ({@code wasVisible}, never set on a server).
 */
@Kind("drill")
public sealed class Drill extends Building implements Checkpointable permits BurstDrill{
    public float progress, warmup, timeDrilled, lastDrillSpeed;
    /** How many tiles under the drill hold {@link #dominantItem}. */
    public int dominantItems;
    /** The item it mines, or -1. */
    public int dominantItem = -1;
    @Derived("the block's parameters, read on first use")
    private Params params;

    /** {@code Drill}'s fields a server reads. */
    record Params(int tier, float drillTime, float hardnessDrillMultiplier, float liquidBoostIntensity, float warmupSpeed,
        float updateEffectChance, int dumpTime, boolean[] blocked, float[] drillMultipliers){
        static Params of(Building building){
            BlockDef block = building.block();
            ContentEpoch content = building.world().content();
            var blocked = new boolean[content.items()];
            var multipliers = new float[content.items()];
            Arrays.fill(multipliers, 1f);
            if(!block.baked()) return new Params(0, 300f, 50f, 1.6f, 0.015f, 0.02f, 5, blocked, multipliers);
            var fields = block.fields();
            List<Object> blockedItems = fields.list("blockedItems");
            if(blockedItems != null) for(Object item : blockedItems) blocked[content.id(ContentType.ITEM, (String)item)] = true;
            fields.map("drillMultipliers").forEach((item, value) -> multipliers[content.id(ContentType.ITEM, item)] = ((Number)value).floatValue());
            return new Params(fields.i("tier"), fields.f("drillTime"), fields.f("hardnessDrillMultiplier"), fields.f("liquidBoostIntensity"),
                fields.f("warmupSpeed"), fields.f("updateEffectChance"), fields.i("dumpTime"), blocked, multipliers);
        }
    }

    final Params own(){
        if(params == null) params = Params.of(this);
        return params;
    }

    /** {@code getDrillTime(item)}: ticks per item, slower for harder ores. */
    public float getDrillTime(int item){
        Params params = own();
        return (params.drillTime() + params.hardnessDrillMultiplier() * world().content().item(item).hardness()) / params.drillMultipliers()[item];
    }

    /** {@code canMine(tile)}: the ore at {@code pos} is one this drill's tier may mine and isn't blocked. */
    public final boolean canMine(int pos){
        TileGrid tiles = world().tiles();
        if(tiles.table().isStatic(tiles.block(pos))) return false;
        int drop = tiles.drop(pos);
        return drop != BlockTable.NONE && world().content().item(drop).hardness() <= own().tier() && !own().blocked()[drop];
    }

    /**
     * {@code countOre(tile)}: sets {@link #dominantItem} and {@link #dominantItems} from the ore under the footprint.
     * Vanilla sorts the items it found (low priority first, then by count, then by id) and takes the last.
     */
    final void countOre(){
        TileGrid tiles = world().tiles();
        ContentEpoch content = world().content();
        int[] counts = new int[content.items()];
        Footprint.each(tiles, tileX(), tileY(), block().size(), pos -> {
            if(canMine(pos)) counts[tiles.drop(pos)]++;
        });
        int best = -1;
        for(int item = 0; item < counts.length; item++){
            if(counts[item] == 0) continue;
            if(best == -1){
                best = item;
                continue;
            }
            int type = Boolean.compare(!content.item(item).lowPriority(), !content.item(best).lowPriority());
            // equal counts: the higher id sorts last
            if(type > 0 || type == 0 && counts[item] >= counts[best]) best = item;
        }
        dominantItem = best;
        dominantItems = best == -1 ? 0 : counts[best];
    }

    @Override
    public boolean shouldConsume(){
        return items.total() < info().itemCapacity && enabled && dominantItem != -1;
    }

    @Override
    public void onProximityUpdate(){
        super.onProximityUpdate();
        countOre();
    }

    @Override
    public void updateTile(){
        Params params = own();
        if(timer(0, params.dumpTime() / timeScale)){
            dump(dominantItem != -1 && items.has(dominantItem) ? dominantItem : -1);
        }

        if(dominantItem == -1) return;

        timeDrilled += warmup * delta();

        float delay = getDrillTime(dominantItem);
        int capacity = info().itemCapacity;

        if(items.total() < capacity && dominantItems > 0 && efficiency > 0){
            float speed = Mathf.lerp(1f, params.liquidBoostIntensity(), optionalEfficiency) * efficiency;

            lastDrillSpeed = (speed * dominantItems * warmup) / delay;
            warmup = Mathf.approachDelta(warmup, speed, params.warmupSpeed());
            progress += delta() * dominantItems * speed * warmup;

            // the update effect: its chance, then its position
            if(Mathf.chanceDelta(world().rand(), params.updateEffectChance() * warmup)){
                float range = block().size() * 2f;
                Mathf.range(world().rand(), range);
                Mathf.range(world().rand(), range);
            }
        }else{
            lastDrillSpeed = 0f;
            warmup = Mathf.approachDelta(warmup, 0f, params.warmupSpeed());
            return;
        }

        if(dominantItems > 0 && progress >= delay && items.total() < capacity){
            int amount = (int)(progress / delay);
            for(int i = 0; i < amount; i++) offload(dominantItem);
            progress %= delay;
        }
    }

    @Override
    public double sense(LAccess sensor){
        if(sensor == LAccess.progress && dominantItem != -1) return progress;
        return super.sense(sensor);
    }

    @Override
    public Object senseObject(LAccess sensor){
        if(sensor == LAccess.firstItem) return dominantItem == -1 ? null : ContentRef.item(dominantItem);
        return super.senseObject(sensor);
    }

    @Override
    public void write(CheckpointOut out){
        out.f("progress", progress).f("warmup", warmup).f("timeDrilled", timeDrilled).f("lastDrillSpeed", lastDrillSpeed)
            .i("dominantItems", dominantItems).i("dominantItem", dominantItem);
    }

    @Override
    public void read(CheckpointIn in){
        progress = in.f("progress");
        warmup = in.f("warmup");
        timeDrilled = in.f("timeDrilled");
        lastDrillSpeed = in.f("lastDrillSpeed");
        dominantItems = in.i("dominantItems");
        dominantItem = in.i("dominantItem");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (Drill)into;
        out.progress = progress;
        out.warmup = warmup;
        out.timeDrilled = timeDrilled;
        out.lastDrillSpeed = lastDrillSpeed;
        out.dominantItems = dominantItems;
        out.dominantItem = dominantItem;
        out.params = params;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.f(progress).f(warmup).f(timeDrilled).f(lastDrillSpeed).i(dominantItems).i(dominantItem);
    }

    /** {@code pickedUp()}: a payload unit lifts it, and its ore goes until it is placed again. */
    @Override
    public void pickedUp(){
        dominantItem = -1;
    }
}
