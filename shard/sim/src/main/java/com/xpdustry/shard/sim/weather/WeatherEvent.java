package com.xpdustry.shard.sim.weather;

import com.xpdustry.shard.sim.world.*;

/** The weather's {@link WorldEvent}s, published where vanilla sends its calls. */
public sealed interface WeatherEvent extends WorldEvent{
    /** {@code Call.createWeather(weather, intensity, duration, windX, windY)}: a new event, already in the world. */
    record Created(int weather, float intensity, float duration, float windX, float windY) implements WeatherEvent{
    }
}
