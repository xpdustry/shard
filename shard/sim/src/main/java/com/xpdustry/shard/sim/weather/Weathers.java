package com.xpdustry.shard.sim.weather;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * A world's weather (WP42): vanilla's {@code Groups.weather}, the rules' schedule ({@code Logic.updateWeather}), its
 * reset when a game starts ({@code Logic}'s {@code PlayEvent} listener), and {@code state.envAttrs}.
 *
 * <p>The schedule lives in the rules: each {@link WeatherEntry}'s {@code cooldown} counts down every tick; once it is
 * below zero (or the entry is {@code always}) and its weather isn't active, the entry draws a duration and its next
 * cooldown, then a wind direction, and creates the event through {@code Call.createWeather}
 * ({@link WeatherEvent.Created}).
 *
 * <p>{@link Persistence#AUTHORITATIVE}: the group's order and the environment attributes, which {@code ENV} computes
 * from opacities that {@code MISC} then moves on, so they can't be rebuilt.
 */
@Register
public final class Weathers implements WorldPart, Checkpointable{
    private final ActiveList<WeatherState> list;
    /** {@code state.envAttrs}: the rules' attributes plus each event's, at its opacity, as of the last {@code ENV}. */
    private Attributes envAttrs = new Attributes();

    public Weathers(){
        list = new ActiveList<>(WeatherState.SLOT);
    }

    private Weathers(Weathers source, CopyContext context){
        list = source.list.copy(context);
        envAttrs = source.envAttrs.copy();
    }

    /** The world's weather, or null in a world built without it. */
    public static Weathers of(World world){
        return world.find(Weathers.class);
    }

    /** {@code Groups.weather}: the active events, in group order. */
    public ActiveList<WeatherState> list(){
        return list;
    }

    /** {@code Weather.instance()}: the first active event of {@code weather}, or null. */
    public WeatherState instance(int weather){
        for(int i = 0; i < list.size(); i++){
            if(list.get(i).weather == weather) return list.get(i);
        }
        return null;
    }

    /** {@code Weather.isActive()}. */
    public boolean isActive(int weather){
        return instance(weather) != null;
    }

    /** {@code state.envAttrs}, as of the last {@code ENV} phase. Don't modify it. */
    public Attributes envAttrs(){
        return envAttrs;
    }

    /**
     * {@code Call.createWeather(weather, intensity, duration, windX, windY)}: the event is created on the server, then
     * the call goes to clients.
     */
    public static WeatherState createWeather(World world, int weather, float intensity, float duration, float windX, float windY){
        WeatherState state = WeatherState.create(world, weather, intensity, duration);
        state.windVector.set(windX, windY);
        world.publish(new WeatherEvent.Created(weather, intensity, duration, windX, windY));
        return state;
    }

    /** {@code Logic.updateWeather()} (Logic.java:375): drops unknown weathers from the rules, then runs the schedule. */
    public static void updateSchedule(World world){
        Rules rules = UnitCalls.rules(world);
        Weathers weathers = of(world);
        if(weathers == null) return;
        int known = world.content().count(ContentType.WEATHER);
        rules.weather.removeIf(entry -> entry.weather < 0 || entry.weather >= known);
        Rand rand = world.rand();
        for(WeatherEntry entry : rules.weather){
            entry.cooldown -= 1f;
            if((entry.cooldown < 0 || entry.always) && !weathers.isActive(entry.weather)){
                float duration = entry.always ? Float.POSITIVE_INFINITY : Mathf.random(rand, entry.minDuration, entry.maxDuration);
                entry.cooldown = duration + Mathf.random(rand, entry.minFrequency, entry.maxFrequency);
                var wind = new Vec2().setToRandomDirection(rand);
                createWeather(world, entry.weather, entry.intensity, duration, wind.x, wind.y);
            }
        }
    }

    /**
     * The weather part of {@code Logic.play()}: its {@code PlayEvent} listener rerolls every entry's cooldown, in a
     * shuffled order, each after the ones before it. The game-start owner calls it where vanilla fires
     * {@code PlayEvent}: after the wave countdown is set, before the loadout.
     */
    public static void play(World world){
        Rules rules = UnitCalls.rules(world);
        Rand rand = world.rand();
        // state.rules.weather.copy().shuffle(): Seq.shuffle swaps from the end, drawing for every index down to 0
        var shuffled = new ArrayList<>(rules.weather);
        for(int i = shuffled.size() - 1; i >= 0; i--){
            int other = Mathf.random(rand, i);
            WeatherEntry temp = shuffled.get(i);
            shuffled.set(i, shuffled.get(other));
            shuffled.set(other, temp);
        }
        float sum = 0f;
        for(WeatherEntry entry : shuffled){
            entry.cooldown = sum + Mathf.random(rand, entry.maxFrequency);
            sum += entry.cooldown;
        }
    }

    /** The weather part of {@code Logic.update}'s {@code envAttrs}: the rules' attributes, plus each event's at its opacity. */
    void updateEnv(World world){
        // envAttrs.clear(), then add(rules.attributes): 0 + value, so a -0 attribute becomes 0
        Attributes out = envAttrs;
        Attributes rules = UnitCalls.rules(world).attributes;
        for(int attribute = 0; attribute < Attributes.NAMES.size(); attribute++) out.set(attribute, 0f + rules.get(attribute) * 1f);
        for(int i = 0; i < list.size(); i++){
            WeatherState state = list.get(i);
            Map<String, Float> attrs = state.type().attrs();
            for(int attribute = 0; attribute < Attributes.NAMES.size(); attribute++){
                Float value = attrs.get(Attributes.NAMES.get(attribute));
                // Attributes.add(other, scl): every attribute, absent ones are 0
                out.set(attribute, out.get(attribute) + (value == null ? 0f : value) * state.opacity);
            }
        }
    }

    @Override
    public WorldPart copy(CopyContext context){
        return new Weathers(this, context);
    }

    @Override
    public void hash(Hasher hasher){
        hasher.i(list.size());
        for(int i = 0; i < list.size(); i++) list.get(i).hashState(hasher);
        envAttrs.hash(hasher);
    }

    @Override
    public void write(CheckpointOut out){
        out.refs("list", list.toList().toArray(WeatherState[]::new), list.size()).obj("envAttrs", envAttrs);
    }

    @Override
    public void read(CheckpointIn in){
        WeatherState[] stored = in.refs("list", WeatherState.class);
        if(stored == null || Arrays.asList(stored).contains(null)) throw new CheckpointException("Groups.weather holds a missing weather state");
        list.restore(Arrays.asList(stored));
        envAttrs = in.obj("envAttrs", Attributes.class);
        if(envAttrs == null) throw new CheckpointException("the environment attributes are missing");
    }
}
