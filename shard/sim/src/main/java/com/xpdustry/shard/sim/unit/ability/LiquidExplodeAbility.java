package com.xpdustry.shard.sim.unit.ability;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.puddle.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code LiquidExplodeAbility} (LiquidExplodeAbility.java): on death, a splat of its liquid around the unit's
 * tile, {@code max((int)(hitSize / 8 * radScale), 1)} tiles out, its edge roughened by simplex noise, each tile's
 * puddle the fuller the closer it is to the middle. Every neoplasm unit carries it. The deposits draw from the random
 * as any puddle does ({@link Puddles#deposit}).
 */
@AbilityKind("liquid-explode-ability")
public final class LiquidExplodeAbility extends Ability{
    private int liquid = -1;
    private float amount, radAmountScale, radScale, noiseMag, noiseScl;

    @Override
    protected void load(AbilityDef def, int index, ContentEpoch content){
        super.load(def, index, content);
        LiquidDef liquid = def.liquid(content);
        this.liquid = liquid == null ? -1 : liquid.id();
        amount = def.amount();
        radAmountScale = def.radAmountScale();
        radScale = def.radScale();
        noiseMag = def.noiseMag();
        noiseScl = def.noiseScl();
    }

    @Override
    public void death(Unit unit, UnitAbility state){
        World world = unit.world();
        TileGrid tiles = world.tiles();
        int tx = unit.tileX(), ty = unit.tileY();
        int rad = Math.max((int)(unit.hitSize / 8f * radScale), 1);
        float realNoise = unit.hitSize / noiseMag;
        for(int x = -rad; x <= rad; x++){
            for(int y = -rad; y <= rad; y++){
                if(x * x + y * y <= rad * rad - Simplex.noise2d(0, 2, 0.5f, 1f / noiseScl, x + tx, y + ty) * realNoise * realNoise){
                    float scaling = (1f - Mathf.dst(x, y) / rad) * radAmountScale;
                    if(tiles.inBounds(tx + x, ty + y)) Puddles.deposit(world, tiles.pos(tx + x, ty + y), liquid, amount * scaling);
                }
            }
        }
    }
}
