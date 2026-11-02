package com.xpdustry.shard.sim.blocks.turret;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.schema.*;
import com.xpdustry.shard.sim.bullet.*;
import com.xpdustry.shard.sim.combat.*;

/**
 * A bullet as a turret's ammo: the {@link BulletType} it fires and the {@code BulletType} fields only turrets read
 * (ammo per item, reload and range changes, the target sort and filters, its own shoot pattern).
 *
 * @param unitSort the bullet's {@code unitSort} ({@code sort:closest} unless it has its own)
 * @param shootPattern the bullet's own pattern, or null for the turret's
 */
public record Ammo(BulletType type, float ammoMultiplier, float reloadMultiplier, float rangeChange, float minRangeChange,
                   String unitSort, boolean targetBlocks, boolean targetMissiles, ShootPattern shootPattern){

    /** The ammo {@code type} makes, read from its definition. */
    public static Ammo of(BulletType type){
        BulletDef def = type.def();
        Struct fields = def.fields();
        Struct pattern = fields.struct("shootPattern");
        return new Ammo(type, def.ammoMultiplier(), def.reloadMultiplier(), def.rangeChange(), fields.f("minRangeChange"),
            fields.s("unitSort"), fields.b("targetBlocks"), fields.b("targetMissiles"), pattern == null ? null : ShootPattern.of(pattern));
    }
}
