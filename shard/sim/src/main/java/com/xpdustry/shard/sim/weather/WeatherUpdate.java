package com.xpdustry.shard.sim.weather;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/** {@code Logic.update}'s weather schedule, which is server-side and off in the editor ({@link Weathers#updateSchedule}). */
@Register
public final class WeatherUpdate implements PhaseHandler{
    @Override
    public Phase phase(){
        return Phase.WEATHER;
    }

    @Override
    public void run(World world){
        if(!UnitCalls.rules(world).editor) Weathers.updateSchedule(world);
    }
}
