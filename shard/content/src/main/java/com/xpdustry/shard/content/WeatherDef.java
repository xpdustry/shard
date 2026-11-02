package com.xpdustry.shard.content;

import java.util.*;

/** The typed, immutable definition of a weather ({@code mindustry.type.Weather}). */
public interface WeatherDef extends ContentDef{
    @Override
    default ContentType type(){
        return ContentType.WEATHER;
    }

    /** Ticks a random occurrence lasts. */
    default float duration(){
        return fields().f("duration");
    }

    default float statusDuration(){
        return fields().f("statusDuration");
    }

    default boolean statusAir(){
        return fields().b("statusAir");
    }

    default boolean statusGround(){
        return fields().b("statusGround");
    }

    default boolean hidden(){
        return fields().b("hidden");
    }

    /**
     * {@code ParticleWeather.force}: each tick, every unit gets an impulse of {@code force * intensity} along the wind.
     * 0 for kinds without the field.
     */
    default float force(){
        return fields().has("force") ? fields().f("force") : 0f;
    }

    /** {@code attrs}: what an active event adds to the environment attributes, at its opacity, by attribute name. */
    default Map<String, Float> attrs(){
        var out = new TreeMap<String, Float>();
        for(var entry : fields().map("attrs").entrySet()) out.put(entry.getKey(), ((Number)entry.getValue()).floatValue());
        return out;
    }

    /** The status it applies to exposed units, or the {@code none} status. */
    StatusDef status();
}
