package com.xpdustry.shard.sim.blocks.turret;

import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.building.consume.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code ContinuousLiquidTurret.ContinuousLiquidTurretBuild} (sublimate): a continuous turret whose bullet
 * is its liquid's, burning {@code liquidConsumed} a frame through its {@code ConsumeLiquidFilter} (divided by the
 * bullet's {@code ammoMultiplier}). It starts firing only with four frames' worth held.
 */
@Kind("continuous-liquid-turret")
public final class ContinuousLiquidTurret extends ContinuousTurretBuild{
    public boolean activated;

    @Override
    public void updateTile(){
        super.updateTile();
        float consumed = type().liquidConsumed;
        // only allow the turret to begin firing when it can fire for 4 continuous updates
        if(liquids.currentAmount() >= consumed * 4f){
            activated = true;
        }else if(liquids.currentAmount() < consumed){
            activated = false;
        }
    }

    @Override
    public Object senseObject(LAccess sensor){
        if(sensor == LAccess.currentAmmoType) return ContentRef.liquid(liquids.current());
        return super.senseObject(sensor);
    }

    /** The consumer's {@code multiplier}: {@code 1 / ammoMultiplier} of the current liquid's bullet. */
    @Override
    public float consumeMultiplier(Consume consume){
        Ammo ammo = type().ammo(liquids.current());
        return ammo == null ? 1f : 1f / ammo.ammoMultiplier();
    }

    @Override
    public boolean canConsume(){
        return hasCorrectAmmo() && super.canConsume();
    }

    @Override
    public boolean shouldConsume(){
        return super.shouldConsume() && activated;
    }

    @Override
    public Ammo peekAmmo(){
        return type().ammo(liquids.current());
    }

    @Override
    public boolean hasAmmo(){
        return hasCorrectAmmo() && type().ammo(liquids.current()) != null && liquids.currentAmount() > 0f && activated;
    }

    public boolean hasCorrectAmmo(){
        if(!bullets.any()) return true;
        var first = bullets.bullet(world(), 0);
        Ammo ammo = peekAmmo();
        return ammo != null && first != null && first.type() == ammo.type();
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

    @Override
    public void write(CheckpointOut out){
        super.write(out);
        out.bool("activated", activated);
    }

    @Override
    public void read(CheckpointIn in){
        super.read(in);
        activated = in.bool("activated");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        ((ContinuousLiquidTurret)into).activated = activated;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.bool(activated);
    }
}
