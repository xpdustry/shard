package com.xpdustry.shard.sim.rules;

import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

/**
 * A weather event of the map (vanilla {@code Weather.WeatherEntry}). {@link #cooldown} is state, not configuration:
 * vanilla rerolls it when a game starts.
 */
public final class WeatherEntry implements Checkpointable{
    /** The weather id, or -1 for vanilla's null (a name the content doesn't know). */
    public int weather = VanillaIds.RAIN;
    /** Spacing between events, not counting the events themselves, in ticks. */
    public float minFrequency, maxFrequency, minDuration, maxDuration;
    /** Ticks until the next event. */
    public float cooldown;
    public float intensity = 1f;
    /** Always active. */
    public boolean always = false;

    public WeatherEntry copy(){
        var out = new WeatherEntry();
        out.weather = weather;
        out.minFrequency = minFrequency;
        out.maxFrequency = maxFrequency;
        out.minDuration = minDuration;
        out.maxDuration = maxDuration;
        out.cooldown = cooldown;
        out.intensity = intensity;
        out.always = always;
        return out;
    }

    public void hash(Hasher hasher){
        hasher.i(weather).f(minFrequency).f(maxFrequency).f(minDuration).f(maxDuration).f(cooldown).f(intensity).bool(always);
    }

    @Override
    public void write(CheckpointOut out){
        out.i("weather", weather).f("minFrequency", minFrequency).f("maxFrequency", maxFrequency).f("minDuration", minDuration)
            .f("maxDuration", maxDuration).f("cooldown", cooldown).f("intensity", intensity).bool("always", always);
    }

    @Override
    public void read(CheckpointIn in){
        weather = in.i("weather");
        minFrequency = in.f("minFrequency");
        maxFrequency = in.f("maxFrequency");
        minDuration = in.f("minDuration");
        maxDuration = in.f("maxDuration");
        cooldown = in.f("cooldown");
        intensity = in.f("intensity");
        always = in.bool("always");
    }
}
