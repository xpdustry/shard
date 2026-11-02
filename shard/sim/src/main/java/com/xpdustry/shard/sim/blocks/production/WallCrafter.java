package com.xpdustry.shard.sim.blocks.production;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.environment.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.building.consume.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code WallCrafter.WallCrafterBuild} (WallCrafter.java): Erekir's cliff crushers. Its speed is the sum of
 * the attribute ({@code sand}) of the solid blocks along its front edge ({@link #efficiencyAt}), boosted by an optional
 * liquid or by an item it burns every {@code boostItemUseTime} ticks; every {@code drillTime} ticks of work it
 * offloads one {@code output} item, while it holds fewer than its capacity of them.
 *
 * <p>Its update effect plays only where a renderer saw the building ({@code wasVisible}), so nothing here draws from
 * the random. {@link #totalTime} only feeds drawing.
 */
@Kind("wall-crafter")
public final class WallCrafter extends Building implements Checkpointable{
    /** {@code timerUse}: the item booster's timer. */
    static final int TIMER_USE = 1;

    public float time, warmup, totalTime, lastEfficiency;
    @Derived("the block's parameters, read on first use")
    private Params params;

    /** {@code WallCrafter}'s fields a server reads. */
    record Params(int attribute, int output, float drillTime, float liquidBoostIntensity, float itemBoostIntensity,
        float boostItemUseTime, int dumpTime, Consume itemConsumer, boolean hasLiquidBooster){
        static Params of(Building building){
            BlockDef block = building.block();
            ContentEpoch content = building.world().content();
            Consumers consumers = building.info().consumers;
            boolean liquidBooster = false;
            for(Consume c : consumers.all()) if(c instanceof ConsumeLiquidBase && c.booster) liquidBooster = true;
            if(!block.baked()){
                return new Params(Attributes.SAND, content.id(ContentType.ITEM, "sand"), 150f, 1.6f, 1.6f, 120f, 5, null, liquidBooster);
            }
            var fields = block.fields();
            // the item booster is the block's item consumer (consumeItem(...).boost())
            Consume itemConsumer = fields.get("itemConsumer") == null ? null : consumers.find(ConsumeItems.class);
            return new Params(Attributes.id(fields.s("attribute")), content.id(ContentType.ITEM, fields.s("output")), fields.f("drillTime"),
                fields.f("liquidBoostIntensity"), fields.f("itemBoostIntensity"), fields.f("boostItemUseTime"), fields.i("dumpTime"),
                itemConsumer, liquidBooster);
        }
    }

    private Params own(){
        if(params == null) params = Params.of(this);
        return params;
    }

    /**
     * {@code getEfficiency(x, y, rotation, null, null)}: the attribute summed over the solid blocks on the side the
     * crusher faces, for the block's size placed at (x, y).
     */
    public float efficiencyAt(int x, int y, int rotation){
        TileGrid tiles = world().tiles();
        int size = block().size();
        int attribute = own().attribute();
        float eff = 0f;
        int cornerX = x - (size - 1) / 2, cornerY = y - (size - 1) / 2;
        for(int i = 0; i < size; i++){
            int rx = 0, ry = 0;
            switch(rotation){
                case 0 -> {
                    rx = cornerX + size;
                    ry = cornerY + i;
                }
                case 1 -> {
                    rx = cornerX + i;
                    ry = cornerY + size;
                }
                case 2 -> {
                    rx = cornerX - 1;
                    ry = cornerY + i;
                }
                case 3 -> {
                    rx = cornerX + i;
                    ry = cornerY - 1;
                }
                default -> {
                }
            }
            if(!tiles.inBounds(rx, ry)) continue;
            int other = tiles.pos(rx, ry);
            if(tiles.solid(other)) eff += tiles.table().attribute(tiles.block(other), attribute);
        }
        return eff;
    }

    @Override
    public void updateTile(){
        Params params = own();
        boolean cons = shouldConsume();
        boolean itemValid = params.itemConsumer() != null && params.itemConsumer().efficiency(this) > 0;

        warmup = Mathf.approachDelta(warmup, Mathf.num(efficiency > 0), 1f / 40f);

        float eff = efficiencyAt(tileX(), tileY(), rotation) * Mathf.lerp(1f, params.liquidBoostIntensity(), params.hasLiquidBooster() ? optionalEfficiency : 0f)
            * (itemValid ? params.itemBoostIntensity() : 1f);

        if(itemValid && eff * efficiency > 0 && timer(TIMER_USE, params.boostItemUseTime() / timeScale)){
            consume();
        }

        lastEfficiency = eff * timeScale * efficiency;

        if(cons && (time += edelta() * eff) >= params.drillTime()){
            offload(params.output());
            time %= params.drillTime();
        }

        totalTime += edelta() * warmup * (eff <= 0f ? 0f : 1f);

        if(timer(0, params.dumpTime() / timeScale)){
            dump(params.output());
        }
    }

    @Override
    public boolean shouldConsume(){
        return items.get(own().output()) < info().itemCapacity;
    }

    @Override
    public void write(CheckpointOut out){
        out.f("time", time).f("warmup", warmup).f("totalTime", totalTime).f("lastEfficiency", lastEfficiency);
    }

    @Override
    public void read(CheckpointIn in){
        time = in.f("time");
        warmup = in.f("warmup");
        totalTime = in.f("totalTime");
        lastEfficiency = in.f("lastEfficiency");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (WallCrafter)into;
        out.time = time;
        out.warmup = warmup;
        out.totalTime = totalTime;
        out.lastEfficiency = lastEfficiency;
        out.params = params;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.f(time).f(warmup).f(totalTime).f(lastEfficiency);
    }
}
