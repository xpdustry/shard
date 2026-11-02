package com.xpdustry.shard.compat.v160.sync;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.weather.*;
import com.xpdustry.shard.wire.v160_7.*;

/** {@code Call.createWeather(weather, intensity, duration, windX, windY)} for a new weather event, to everyone. */
@Register
public final class WeatherCallMapping implements CallMapping<WeatherEvent>{
    @Override
    public Class<WeatherEvent> event(){
        return WeatherEvent.class;
    }

    @Override
    public void map(WeatherEvent event, CallContext context, CallSink sink){
        switch(event){
            case WeatherEvent.Created created -> sink.all(new Packets.CreateWeather((short)created.weather(), created.intensity(), created.duration(),
                created.windX(), created.windY()));
        }
    }
}
