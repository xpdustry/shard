package com.xpdustry.shard.sim.puddle;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * {@code CellLiquid} (neoplasm): with fire on in the rules, a puddle of it eats the liquid it spreads into (its
 * {@code spreadTarget}) from neighbouring buildings and puddles, and damages the building it lies on. A plain
 * {@code Liquid}'s {@code update(puddle)} and {@code react} do nothing.
 */
final class CellLiquid{
    static final KindKey KIND = KindKey.of("cell-liquid");

    private CellLiquid(){
    }

    /** {@code liquid.react(other, amount, tile, x, y)}: a cell liquid takes in all of its spread target. */
    static float react(LiquidDef liquid, LiquidDef other, float amount){
        if(!liquid.fields().kind().isA(KIND.name())) return 0f;
        String target = liquid.spreadTarget();
        return target != null && target.equals(other.name()) ? amount : 0f;
    }

    /** {@code liquid.update(puddle)} (CellLiquid.java:29). */
    static void update(Puddle puddle, LiquidDef liquid){
        World world = puddle.world();
        if(!liquid.fields().kind().isA(KIND.name()) || !UnitCalls.rules(world).fire) return;
        String targetName = liquid.spreadTarget();
        if(targetName == null) return;
        ContentEpoch content = world.content();
        LiquidDef targetDef = content.liquid(targetName);
        if(targetDef == null) return;
        int target = targetDef.id(), self = liquid.id();
        float maxSpread = liquid.maxSpread(), spreadConversion = liquid.spreadConversion(), spreadDamage = liquid.spreadDamage(),
            removeScaling = liquid.removeScaling();
        TileGrid tiles = world.tiles();
        Buildings buildings = world.buildings();
        int px = tiles.x(puddle.tile), py = tiles.y(puddle.tile);
        float scaling = Mathf.pow(Mathf.clamp(puddle.amount / Puddle.MAX_LIQUID), 2f);
        for(Point2 point : Geometry.d4c){
            int tile = nearby(tiles, px + point.x(), py + point.y());
            Building build = tile < 0 ? null : buildings.at(tile);
            if(build != null && build.liquids != null && build.liquids.get(target) > 0.0001f){
                float amount = Math.min(build.liquids.get(target), maxSpread * scaling);
                build.liquids.remove(target, amount * removeScaling);
                Puddles.deposit(world, tile, self, amount * spreadConversion);
            }
        }
        // damage the thing it is on
        Building on = buildings.at(puddle.tile);
        if(spreadDamage > 0 && on != null && on.liquids != null && on.liquids.get(target) > 0.0001f){
            // spread in 4 adjacent directions around the thing it is on
            float amountSpread = Math.min(on.liquids.get(target) * spreadConversion, maxSpread) / 2f;
            for(Point2 dir : Geometry.d4){
                int other = nearby(tiles, px + dir.x(), py + dir.y());
                if(other >= 0) Puddles.deposit(world, puddle.tile, other, puddle.liquid, amountSpread, true, false);
            }
            on.damage(spreadDamage * scaling);
        }
        // spread to nearby puddles
        for(Point2 point : Geometry.d4){
            int tile = nearby(tiles, px + point.x(), py + point.y());
            if(tile < 0) continue;
            Puddle other = Puddles.get(world, tile);
            if(other != null && other.liquid == target){
                float amount = Math.min(other.amount, Math.max(maxSpread * scaling, other.amount * 0.25f * scaling));
                other.amount -= amount;
                puddle.amount += amount;
                if(other.amount <= Puddle.MAX_LIQUID / 3f){
                    other.remove();
                    Puddles.deposit(world, tile, puddle.tile, self, Math.max(amount, Puddle.MAX_LIQUID / 3f), true, false);
                }
            }
        }
    }

    private static int nearby(TileGrid tiles, int x, int y){
        return tiles.inBounds(x, y) ? tiles.pos(x, y) : -1;
    }
}
