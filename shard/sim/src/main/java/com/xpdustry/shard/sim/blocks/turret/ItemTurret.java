package com.xpdustry.shard.sim.blocks.turret;

import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.building.*;

/**
 * Vanilla's {@code ItemTurret.ItemTurretBuild}: ammo from items. Each item adds its bullet's {@code ammoMultiplier}
 * to an entry of its own, which moves to the top (the next shot's) when the item arrives; the turret takes items while
 * the total stays within {@code maxAmmo}. Items never enter the item module, and can't be taken out.
 *
 * <p>Its {@code ConsumeItemFilter} (added by the bake's {@code ItemTurret.init}) counts as met while the top entry
 * holds a shot, through {@link #consumeTriggerValid()}, as vanilla's overridden {@code efficiency} does.
 */
@Kind("item-turret")
public final class ItemTurret extends TurretBuild{
    @Override
    public void onProximityAdded(){
        super.onProximityAdded();
        // add first ammo item to cheaty blocks so they can shoot properly
        TurretType t = type();
        if(!hasAmmo() && cheating() && t.ammoOrder.length > 0) handleItem(this, t.ammoOrder[0]);
    }

    /** The {@code ConsumeItemFilter}'s {@code efficiency}: valid when it can shoot. */
    @Override
    public boolean consumeTriggerValid(){
        return ammoSize > 0 && (ammoAmounts[ammoSize - 1] >= type().ammoPerShot || cheating());
    }

    @Override
    public Object senseObject(LAccess sensor){
        if(sensor == LAccess.currentAmmoType) return ammoSize > 0 ? ContentRef.item(ammoIds[ammoSize - 1]) : null;
        return super.senseObject(sensor);
    }

    @Override
    public int acceptStack(int item, int amount, int sourceTeam){
        Ammo type = type().ammo(item);
        if(type == null) return 0;
        return Math.min((int)((type().maxAmmo - totalAmmo) / type.ammoMultiplier()), amount);
    }

    @Override
    public void handleStack(int item, int amount, int sourceTeam){
        for(int i = 0; i < amount; i++) handleItem(null, item);
    }

    /** Currently can't remove items from turrets. */
    @Override
    public int removeStack(int item, int amount){
        return 0;
    }

    @Override
    public void handleItem(Building source, int item){
        Ammo type = type().ammo(item);
        if(type == null) return;
        // vanilla's int += float: the sum is truncated
        totalAmmo = (int)(totalAmmo + type.ammoMultiplier());
        // find ammo entry by type
        for(int i = 0; i < ammoSize; i++){
            // if found, put it to the right
            if(ammoIds[i] == item){
                ammoAmounts[i] = (int)(ammoAmounts[i] + type.ammoMultiplier());
                swapAmmo(i, ammoSize - 1);
                return;
            }
        }
        // must not be found
        addAmmo(item, (int)type.ammoMultiplier());
    }

    @Override
    public boolean acceptItem(Building source, int item){
        Ammo type = type().ammo(item);
        return type != null && totalAmmo + type.ammoMultiplier() <= type().maxAmmo;
    }

    /** {@code read}'s ammo: an entry for each valid ammo item, its amount capped at {@code maxAmmo}. */
    public void readAmmo(int[] items, int[] amounts){
        ammoSize = 0;
        totalAmmo = 0;
        for(int i = 0; i < items.length; i++){
            int amount = Math.min(amounts[i], type().maxAmmo);
            // only add ammo if this is a valid ammo type
            if(type().ammo(items[i]) != null){
                totalAmmo += amount;
                addAmmo(items[i], amount);
            }
        }
    }
}
