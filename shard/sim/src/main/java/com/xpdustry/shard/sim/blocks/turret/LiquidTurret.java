package com.xpdustry.shard.sim.blocks.turret;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.combat.*;
import com.xpdustry.shard.sim.fire.*;
import com.xpdustry.shard.sim.tile.*;

/**
 * Vanilla's {@code LiquidTurret.LiquidTurretBuild}: ammo from the liquid it holds, {@code 1 / ammoMultiplier} a shot.
 * It takes a new liquid only once the old one is down to a shot. With a liquid that puts fires out, it first aims at
 * the nearest fire within its range on a free tile or its own team's building ({@link Hazards#hasFire}), then at
 * enemies. It keeps the fire's object ({@link Fire#object}), as vanilla keeps the {@code Fire}.
 *
 * <p>Its {@code ConsumeLiquidFilter} (the bake's {@code LiquidTurret.init}) uses nothing per frame: vanilla overrides
 * its {@code update} to do nothing, which {@code Consumers} reads as {@code update = false}.
 */
@Kind("liquid-turret")
public final class LiquidTurret extends TurretBuild{
    @Override
    public Object senseObject(LAccess sensor){
        if(sensor == LAccess.currentAmmoType) return ContentRef.liquid(liquids.current());
        return super.senseObject(sensor);
    }

    @Override
    protected void findTarget(){
        TurretType t = type();
        LiquidDef current = world().content().liquid(liquids.current());
        if(t.extinguish && current.flammability() < 0.1f && current.temperature() <= 0.5f){
            TileGrid tiles = world().tiles();
            Hazards hazards = Hazards.of(world());
            int tx = Math.round(x() / Footprint.TILE_SIZE), ty = Math.round(y() / Footprint.TILE_SIZE);
            Fire result = null;
            float mindst = 0f;
            int tr = (int)(t.range / Footprint.TILE_SIZE);
            for(int x = -tr; x <= tr; x++){
                for(int y = -tr; y <= tr; y++){
                    int fx = x + tx, fy = y + ty;
                    // Fires.get(x, y) != null && Fires.has(x, y): one live fire on exactly this tile
                    if(!tiles.inBounds(fx, fy) || !hazards.hasFire(fx, fy)) continue;
                    Fire fire = Fires.get(world(), fx, fy);
                    float dst = Mathf.dst2(x(), y(), fire.x, fire.y);
                    Building other = world().buildings().at(fx, fy);
                    // do not extinguish fires on other team blocks
                    if(other != this && dst <= t.range * t.range && (result == null || dst < mindst) && (other == null || other.team == team)){
                        result = fire;
                        mindst = dst;
                    }
                }
            }
            if(result != null){
                clearTarget();
                targetFire = result.object;
                // don't run standard targeting
                return;
            }
        }
        super.findTarget();
    }

    @Override
    public Ammo useAmmo(){
        Ammo type = type().ammo(liquids.current());
        if(cheating()) return type;
        liquids.remove(liquids.current(), 1f / type.ammoMultiplier());
        return type;
    }

    @Override
    public Ammo peekAmmo(){
        return type().ammo(liquids.current());
    }

    @Override
    public boolean hasAmmo(){
        Ammo type = type().ammo(liquids.current());
        return type != null && liquids.currentAmount() >= 1f / type.ammoMultiplier();
    }

    @Override
    public boolean acceptItem(Building source, int item){
        return false;
    }

    @Override
    public boolean acceptLiquid(Building source, int liquid){
        TurretType t = type();
        Ammo current = t.ammo(liquids.current());
        return t.ammo(liquid) != null
            && (liquids.current() == liquid || current == null || liquids.get(liquids.current()) <= 1f / current.ammoMultiplier() + 0.001f);
    }
}
