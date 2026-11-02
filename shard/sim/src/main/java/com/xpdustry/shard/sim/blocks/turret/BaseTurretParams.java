package com.xpdustry.shard.sim.blocks.turret;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.schema.*;
import com.xpdustry.shard.sim.building.consume.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * The fields of vanilla's {@code BaseTurret} and {@code ReloadTurret} that the support turrets read: point defence and
 * the tractor beam, which aren't {@code Turret}s and so don't have a {@link TurretType}.
 */
record BaseTurretParams(float range, float rotateSpeed, float activationTime, float coolantMultiplier, ConsumeLiquidBase coolant,
                        float reload){
    static BaseTurretParams of(World world, BlockDef block, float rotateSpeed, float coolantMultiplier, float reload){
        if(!block.baked()) return new BaseTurretParams(80f, rotateSpeed, 0f, coolantMultiplier, null, reload);
        Struct f = block.fields();
        return new BaseTurretParams(f.f("range"), f.f("rotateSpeed"), f.f("activationTime"), f.f("coolantMultiplier"), coolant(world, block),
            f.has("reload") ? f.f("reload") : reload);
    }

    /** {@code coolant}: the consumer the block's {@code coolant} field names, matched in its consumer list, or null. */
    static ConsumeLiquidBase coolant(World world, BlockDef block){
        Struct wanted = block.fields().struct("coolant");
        if(wanted == null) return null;
        List<Struct> consumes = block.fields().structs("consumes");
        Consume[] all = world.buildings().info(block).consumers.all();
        for(int i = 0; i < consumes.size() && i < all.length; i++){
            if(consumes.get(i).equals(wanted) && all[i] instanceof ConsumeLiquidBase liquid) return liquid;
        }
        return null;
    }
}
