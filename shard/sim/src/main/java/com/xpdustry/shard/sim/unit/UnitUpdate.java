package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code Groups.unit.update()}: every added unit, in list order. WP13 and WP21 add the wave-countdown
 * filter vanilla applies ({@code u.isPlayer() || u.spawnedByCore}) and the physics passes before it.
 */
@Register
public final class UnitUpdate implements PhaseHandler{
    @Override
    public Phase phase(){
        return Phase.UNITS;
    }

    @Override
    public void run(World world){
        world.units().each(Unit::update);
    }
}
