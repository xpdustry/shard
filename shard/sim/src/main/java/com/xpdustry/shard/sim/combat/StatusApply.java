package com.xpdustry.shard.sim.combat;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * {@code StatusComp.apply(effect, duration)}, which bullets, splash and weapons call. A world finds it as the first
 * {@link WorldPart} implementing it, so a test can stand in; otherwise it is the unit's own {@link Statuses}
 * ({@link #STATUSES}).
 */
public interface StatusApply{
    /** {@code unit.apply(status, duration)} on the unit's statuses, which skip {@code none} and immunities. */
    StatusApply STATUSES = (unit, status, duration) -> {
        if(status != null) unit.statuses.apply(unit, status.id(), duration);
    };

    /** Applies {@code status} for {@code duration} ticks; {@code none} and immune units do nothing. */
    void apply(Unit unit, StatusDef status, float duration);

    static StatusApply of(World world){
        StatusApply apply = world.find(StatusApply.class);
        return apply == null ? STATUSES : apply;
    }
}
