package com.xpdustry.shard.sim.weather;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.world.*;

/** {@code Logic.update}'s {@code state.envAttrs}, right after the wave countdown and before the entity update. */
@Register
public final class EnvUpdate implements PhaseHandler{
    @Override
    public Phase phase(){
        return Phase.ENV;
    }

    @Override
    public void run(World world){
        Weathers weathers = Weathers.of(world);
        if(weathers != null) weathers.updateEnv(world);
    }
}
