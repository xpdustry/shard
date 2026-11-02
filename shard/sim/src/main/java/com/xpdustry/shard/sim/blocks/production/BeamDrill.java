package com.xpdustry.shard.sim.blocks.production;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code BeamDrill.BeamDrillBuild} (BeamDrill.java): Erekir's plasma bores. Each tile of its front edge
 * casts a beam up to {@code range} tiles ahead ({@link #lasers}); the first solid tile it meets is mined if its wall
 * drop is one the bore may mine ({@link #facing}). Every {@code drillTime} ticks of work, sped up by the optional
 * booster, each facing tile adds its drop to the bore, while it has room. The facing tiles are found again every tick,
 * so a wall broken or built in a beam counts at once.
 *
 * <p>{@link #lastItem} is the item every beam mines, or -1 when they mine different items; vanilla times a cycle by
 * that item's multiplier ({@code getDrillTime(null)} is the base time).
 */
@Kind("beam-drill")
public final class BeamDrill extends Building implements Checkpointable{
    /** Per front-edge tile: the tile its beam mines ({@code y * width + x}), or -1. */
    public int[] facing = new int[0];
    /** Per front-edge tile: where its beam starts, {@link Point2#pack}ed; null until the first proximity update. */
    public int[] lasers;
    /** The item every beam mines, or -1. */
    public int lastItem = -1;
    public float time, warmup, boostWarmup, lastDrillSpeed;
    public int facingAmount;
    @Derived("the block's parameters, read on first use")
    private Params params;

    /** {@code BeamDrill}'s fields a server reads. */
    record Params(int tier, int range, float drillTime, float optionalBoostIntensity, int dumpTime, boolean[] blocked, float[] drillMultipliers){
        static Params of(Building building){
            BlockDef block = building.block();
            ContentEpoch content = building.world().content();
            var blocked = new boolean[content.items()];
            var multipliers = new float[content.items()];
            Arrays.fill(multipliers, 1f);
            if(!block.baked()) return new Params(1, 5, 200f, 2.5f, 5, blocked, multipliers);
            var fields = block.fields();
            List<Object> blockedItems = fields.list("blockedItems");
            if(blockedItems != null) for(Object item : blockedItems) blocked[content.id(ContentType.ITEM, (String)item)] = true;
            fields.map("drillMultipliers").forEach((item, value) -> multipliers[content.id(ContentType.ITEM, item)] = ((Number)value).floatValue());
            return new Params(fields.i("tier"), fields.i("range"), fields.f("drillTime"), fields.f("optionalBoostIntensity"),
                fields.i("dumpTime"), blocked, multipliers);
        }
    }

    private Params own(){
        if(params == null) params = Params.of(this);
        return params;
    }

    @Override
    public void created(){
        facing = new int[block().size()];
        Arrays.fill(facing, -1);
    }

    /** {@code getDrillTime(item)}; -1 (vanilla's null) reads no multiplier. */
    public float getDrillTime(int item){
        return own().drillTime() / (item == -1 ? 1f : own().drillMultipliers()[item]);
    }

    @Override
    public void updateTile(){
        Params params = own();
        if(lasers == null) updateLasers();

        warmup = Mathf.approachDelta(warmup, Mathf.num(efficiency > 0), 1f / 60f);

        updateFacing();

        float multiplier = Mathf.lerp(1f, params.optionalBoostIntensity(), optionalEfficiency);
        float drillTime = getDrillTime(lastItem);
        boostWarmup = Mathf.lerpDelta(boostWarmup, optionalEfficiency, 0.1f);
        lastDrillSpeed = (facingAmount * multiplier * timeScale) / drillTime * efficiency;

        time += edelta() * multiplier;

        if(time >= drillTime){
            TileGrid tiles = world().tiles();
            for(int tile : facing){
                int drop = tile == -1 ? BlockTable.NONE : tiles.wallDrop(tile);
                if(items.total() < info().itemCapacity && drop != BlockTable.NONE){
                    items.add(drop, 1);
                    produced(drop, 1);
                }
            }
            time %= drillTime;
        }

        if(timer(0, params.dumpTime() / timeScale)){
            dump();
        }
    }

    @Override
    public boolean shouldConsume(){
        return items.total() < info().itemCapacity && facingAmount > 0 && enabled;
    }

    /** Vanilla's comment: when rotated. It doesn't wake the bore. */
    @Override
    public void onProximityUpdate(){
        updateLasers();
        updateFacing();
    }

    /** {@code updateLasers()}: the front edge's tiles, {@code nearbySide} of each. */
    void updateLasers(){
        int size = block().size();
        if(lasers == null) lasers = new int[size];
        int cornerX = tileX() - (size - 1) / 2, cornerY = tileY() - (size - 1) / 2;
        for(int i = 0; i < size; i++){
            lasers[i] = switch(rotation){
                case 0 -> Point2.pack(cornerX + size, cornerY + i);
                case 1 -> Point2.pack(cornerX + i, cornerY + size);
                case 2 -> Point2.pack(cornerX - 1, cornerY + i);
                default -> Point2.pack(cornerX + i, cornerY - 1);
            };
        }
    }

    /** {@code updateFacing()}: the first solid tile in each beam, kept if the bore may mine its wall drop. */
    void updateFacing(){
        Params params = own();
        TileGrid tiles = world().tiles();
        ContentEpoch content = world().content();
        lastItem = -1;
        boolean multiple = false;
        int dx = Geometry.d4x(rotation), dy = Geometry.d4y(rotation);
        facingAmount = 0;

        for(int p = 0; p < block().size(); p++){
            int lx = Point2.x(lasers[p]), ly = Point2.y(lasers[p]);
            int dest = -1;
            for(int i = 0; i < params.range(); i++){
                int rx = lx + dx * i, ry = ly + dy * i;
                if(!tiles.inBounds(rx, ry)) continue;
                int other = tiles.pos(rx, ry);
                if(tiles.solid(other)){
                    int drop = tiles.wallDrop(other);
                    if(drop != BlockTable.NONE && content.item(drop).hardness() <= params.tier() && !params.blocked()[drop]){
                        facingAmount++;
                        if(lastItem != drop && lastItem != -1) multiple = true;
                        lastItem = drop;
                        dest = other;
                    }
                    break;
                }
            }
            facing[p] = dest;
        }

        // when multiple items are present, count that as no item
        if(multiple) lastItem = -1;
    }

    @Override
    public void write(CheckpointOut out){
        out.ints("facing", facing).ints("lasers", lasers)
            .i("lastItem", lastItem).f("time", time).f("warmup", warmup).f("boostWarmup", boostWarmup)
            .f("lastDrillSpeed", lastDrillSpeed).i("facingAmount", facingAmount);
    }

    @Override
    public void read(CheckpointIn in){
        facing = in.ints("facing");
        lasers = in.ints("lasers");
        lastItem = in.i("lastItem");
        time = in.f("time");
        warmup = in.f("warmup");
        boostWarmup = in.f("boostWarmup");
        lastDrillSpeed = in.f("lastDrillSpeed");
        facingAmount = in.i("facingAmount");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (BeamDrill)into;
        out.facing = facing.clone();
        out.lasers = lasers == null ? null : lasers.clone();
        out.lastItem = lastItem;
        out.time = time;
        out.warmup = warmup;
        out.boostWarmup = boostWarmup;
        out.lastDrillSpeed = lastDrillSpeed;
        out.facingAmount = facingAmount;
        out.params = params;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.ints(facing).bool(lasers != null);
        if(lasers != null) hasher.ints(lasers);
        hasher.i(lastItem).f(time).f(warmup).f(boostWarmup).f(lastDrillSpeed).i(facingAmount);
    }
}
