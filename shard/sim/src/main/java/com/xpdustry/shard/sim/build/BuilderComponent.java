package com.xpdustry.shard.sim.build;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.unit.*;

/**
 * {@code BuilderComp.update}, in every unit class (between {@code VelComp} and {@code HealthComp}): {@link Builder#update}.
 * A type that can't build returns at once, as vanilla's first line does.
 */
@Register
public final class BuilderComponent implements UnitComponent{
    @Override
    public String name(){
        return "BuilderComp";
    }

    @Override
    public boolean applies(UnitParams params){
        return true;
    }

    @Override
    public void update(Unit unit){
        Builder.update(unit);
    }
}
