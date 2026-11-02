package com.xpdustry.shard.sim.unit.ability;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.puddle.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code LiquidRegenAbility} (LiquidRegenAbility.java): a damaged unit that isn't flying drinks the puddles
 * of its liquid under it, a disc of tiles {@code max((int)(hitSize / 8 * 0.6), 1)} wide, up to {@code slurpSpeed} from
 * each, and heals {@code regenPerSlurp} per unit drunk. Having drunk, it draws its effect's chance and, on success, the
 * effect's position. Every neoplasm unit carries it.
 */
@AbilityKind("liquid-regen-ability")
public final class LiquidRegenAbility extends Ability{
    private int liquid = -1;
    private float slurpSpeed, regenPerSlurp, slurpEffectChance;

    @Override
    protected void load(AbilityDef def, int index, ContentEpoch content){
        super.load(def, index, content);
        LiquidDef liquid = def.liquid(content);
        this.liquid = liquid == null ? -1 : liquid.id();
        slurpSpeed = def.slurpSpeed();
        regenPerSlurp = def.regenPerSlurp();
        slurpEffectChance = def.slurpEffectChance();
    }

    @Override
    public void update(Unit unit, UnitAbility state){
        if(!unit.damaged() || unit.isFlying()) return;
        World world = unit.world();
        TileGrid tiles = world.tiles();
        boolean healed = false;
        int tx = unit.tileX(), ty = unit.tileY();
        int rad = Math.max((int)(unit.hitSize / 8f * 0.6f), 1);
        for(int x = -rad; x <= rad; x++){
            for(int y = -rad; y <= rad; y++){
                if(x * x + y * y > rad * rad || !tiles.inBounds(tx + x, ty + y)) continue;
                Puddle puddle = Puddles.get(world, tiles.pos(tx + x, ty + y));
                if(puddle != null && puddle.liquid == liquid){
                    // slurpSpeed * Time.delta, at Δ = 1
                    float fractionTaken = Math.min(puddle.amount, slurpSpeed);
                    puddle.amount -= Math.min(puddle.amount, slurpSpeed);
                    unit.heal(fractionTaken * regenPerSlurp);
                    healed = true;
                }
            }
        }
        if(healed){
            Rand rand = world.rand();
            if(Mathf.chanceDelta(rand, slurpEffectChance)){
                // Tmp.v1.rnd(Mathf.random(unit.hitSize / 2f)): the length, then the direction
                float length = Mathf.random(rand, unit.hitSize / 2f);
                new Vec2().setToRandomDirection(rand).scl(length);
            }
        }
    }
}
