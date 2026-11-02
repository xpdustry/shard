package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.sim.world.*;

/**
 * The spawn knockback of {@code UnitComp.update} (WP47): when {@code state.hasSpawns()}, units of other teams than the
 * wave team are pushed out of each ground spawn's drop zone. A world finds it as the first {@link WorldPart}
 * implementing it; without one, no unit is pushed ({@link #NONE}).
 */
public interface DropZones{
    DropZones NONE = unit -> {};

    /**
     * For a hittable unit not on the wave team: {@code velAddNet} away from each spawn within
     * {@code rules.dropZoneRadius + hitSize / 2 + 1}, as vanilla's loop over {@code spawner.getSpawns()}.
     */
    void push(Unit unit);

    static DropZones of(World world){
        DropZones zones = world.find(DropZones.class);
        return zones == null ? NONE : zones;
    }
}
