package com.xpdustry.shard.compat.v160.entity;

import com.xpdustry.shard.sim.weather.*;
import com.xpdustry.shard.wire.*;
import com.xpdustry.shard.wire.v160_7.*;

/** One weather event, frozen for {@link WeatherStateLayout}: its id, then {@code WeatherState}'s synced fields. */
public final class WeatherStateProjection implements WeatherStateLayout.Source{
    private final int id;
    private final float effectTimer, intensity, life, opacity, x, y;
    private final short weather;
    private final Codecs.Vec2 windVector;

    WeatherStateProjection(WeatherState state){
        id = state.id();
        effectTimer = state.effectTimer;
        intensity = state.intensity;
        life = state.life;
        opacity = state.opacity;
        weather = (short)state.weather;
        windVector = new Codecs.Vec2(state.windVector.x, state.windVector.y);
        x = state.x;
        y = state.y;
    }

    /** The event's entity id. */
    public int id(){
        return id;
    }

    @Override
    public float effectTimer(){
        return effectTimer;
    }

    @Override
    public float intensity(){
        return intensity;
    }

    @Override
    public float life(){
        return life;
    }

    @Override
    public float opacity(){
        return opacity;
    }

    @Override
    public short weather(){
        return weather;
    }

    @Override
    public Codecs.Vec2 windVector(){
        return windVector;
    }

    @Override
    public float x(){
        return x;
    }

    @Override
    public float y(){
        return y;
    }
}
