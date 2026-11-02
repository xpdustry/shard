package com.xpdustry.shard.sim.blocks.production;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.blocks.environment.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.weather.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code SolidPump.SolidPumpBuild} (SolidPump.java): the water extractor, and the base of the oil extractor
 * ({@link Fracker}). It makes {@code result} from solid floor: each non-liquid tile under it counts
 * {@code baseEfficiency} ({@link #validTiles}), and the floors' attribute ({@link #boost}) and the environment's add
 * to that; it fills itself at {@code pumpAmount} times that fraction while it works, and dumps the liquid.
 *
 * <p>Vanilla quirks kept:
 * <ul>
 *   <li>its {@code onProximityUpdate} calls {@code super.onProximityAdded()} instead (SolidPump.java:151), so any
 *   change next to one merges its power graph again, and it never wakes;</li>
 *   <li>{@code SolidPumpBuild} declares a {@code warmup} of its own, hiding the pump's, which then stays 0; only
 *   drawing reads the pump's, so Shard keeps the one field, {@link #warmup}, as the solid pump's.</li>
 * </ul>
 *
 * <p>The update effect's chance and position draw from the world's random while it pumps, as vanilla's do on a
 * server. The environment's attribute is {@code state.envAttrs} ({@link #env}).
 */
@Kind("solid-pump")
public sealed class SolidPump extends Pump permits Fracker{
    public float pumpTime, boost, validTiles, lastPump;
    @Derived("the block's parameters, read on first use")
    private Params params;

    /** {@code SolidPump}'s fields a server reads. */
    record Params(int result, int attribute, float baseEfficiency, float updateEffectChance, boolean floats){
        static Params of(Building building){
            BlockDef block = building.block();
            ContentEpoch content = building.world().content();
            if(!block.baked()) return new Params(content.id(ContentType.LIQUID, "water"), -1, 1f, 0.02f, true);
            var fields = block.fields();
            String attribute = fields.s("attribute");
            return new Params(content.id(ContentType.LIQUID, fields.s("result")), attribute == null ? -1 : Attributes.id(attribute),
                fields.f("baseEfficiency"), fields.f("updateEffectChance"), block.floating() || block.placeableLiquid());
        }
    }

    final Params solid(){
        if(params == null) params = Params.of(this);
        return params;
    }

    /** {@code attribute.env()}: {@code state.envAttrs}, the rules' attributes plus the weather's, as of this tick's {@code ENV}. */
    final float env(int attribute){
        Weathers weathers = Weathers.of(world());
        return weathers != null ? weathers.envAttrs().get(attribute) : rules().attributes.get(attribute);
    }

    @Override
    public boolean shouldConsume(){
        return liquids.get(solid().result()) < info().liquidCapacity - 0.01f;
    }

    @Override
    public void updateTile(){
        Params params = solid();
        float capacity = info().liquidCapacity;
        int result = params.result();
        liquidDrop = result;
        float fraction = Math.max(validTiles + boost + (params.attribute() == -1 ? 0 : env(params.attribute())), 0);

        if(efficiency > 0 && liquids.get(result) < capacity - 0.001f){
            float maxPump = Math.min(capacity - liquids.get(result), pump().pumpAmount() * delta() * fraction * efficiency);
            liquids.add(result, maxPump);
            lastPump = maxPump;
            warmup = Mathf.lerpDelta(warmup, 1f, 0.02f);
            // the update effect: its chance, then its position
            if(Mathf.chance(world().rand(), delta() * params.updateEffectChance())){
                float range = block().size() * 2f;
                Mathf.range(world().rand(), range);
                Mathf.range(world().rand(), range);
            }
        }else{
            warmup = Mathf.lerpDelta(warmup, 0f, 0.02f);
            lastPump = 0f;
        }

        pumpTime += warmup * edelta();

        dumpLiquid(result);
    }

    @Override
    public double sense(LAccess sensor){
        if(sensor == LAccess.efficiency) return (validTiles + boost) * efficiency;
        return super.sense(sensor);
    }

    @Override
    public void onProximityUpdate(){
        // vanilla quirk kept: the power graph's hook, not the pump's (SolidPump.java:151)
        onProximityAdded();

        Params params = solid();
        int size = block().size();
        TileGrid tiles = world().tiles();
        boost = params.attribute() == -1 ? 0f : tiles.sumAttribute(params.attribute(), tileX(), tileY(), size, params.floats()) / size / size;
        validTiles = 0f;
        Footprint.each(tiles, tileX(), tileY(), size, pos -> {
            if(!tiles.isLiquid(pos)) validTiles += params.baseEfficiency() / (size * size);
        });
    }

    @Override
    public void write(CheckpointOut out){
        super.write(out);
        out.f("pumpTime", pumpTime).f("boost", boost).f("validTiles", validTiles).f("lastPump", lastPump);
    }

    @Override
    public void read(CheckpointIn in){
        super.read(in);
        pumpTime = in.f("pumpTime");
        boost = in.f("boost");
        validTiles = in.f("validTiles");
        lastPump = in.f("lastPump");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (SolidPump)into;
        out.pumpTime = pumpTime;
        out.boost = boost;
        out.validTiles = validTiles;
        out.lastPump = lastPump;
        out.params = params;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.f(pumpTime).f(boost).f(validTiles).f(lastPump);
    }

    /** {@code pickedUp()}: a payload unit lifts it, and its floor boost and tiles go (it replaces the pump's). */
    @Override
    public void pickedUp(){
        boost = validTiles = 0f;
    }
}
