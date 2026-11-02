package com.xpdustry.shard.sim.weather;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * One weather event (vanilla's pooled {@code WeatherState} entity, {@code Weather.WeatherStateComp}): it fades in,
 * lasts {@link #life} ticks, pushes and afflicts units, and fades out. It is in {@code Groups.all} (its update, from
 * {@code MISC}), {@code Groups.sync} (snapshots carry it) and {@code Groups.weather} ({@link Weathers}).
 *
 * <p>Creation is {@code Weather.create(intensity, duration)}: a pooled object keeps the id it was freed with; a new
 * one draws its id, then its wind direction from the random (the constructor's {@code setToRandomDirection()}).
 * Vanilla's reused objects keep their old wind until {@code createWeather} sets it; Shard's start at zero, which only a
 * direct {@code create} without a wind would show.
 *
 * <p>Every field is {@link Persistence#AUTHORITATIVE} but the group position. x and y are {@code PosComp}'s, never
 * moved on a server.
 */
public final class WeatherState extends Entity implements Checkpointable{
    /** {@code WeatherStateComp.fadeTime}: ticks of fading in or out. */
    public static final float FADE_TIME = 60 * 4;

    static final ActiveList.Slot<WeatherState> SLOT = new ActiveList.Slot<>(){
        @Override
        public int get(WeatherState state){
            return state.weatherIndex;
        }

        @Override
        public void set(WeatherState state, int position){
            state.weatherIndex = position;
        }
    };

    @Derived("Weathers.read: the position in Groups.weather")
    int weatherIndex = -1;

    /** The weather's content id. */
    public int weather = -1;
    public float intensity = 1f, opacity = 0f, life, effectTimer;
    public Vec2 windVector = new Vec2();
    public float x, y;

    /** A blank state for a checkpoint or fork to fill. */
    public WeatherState(){
    }

    /**
     * {@code Weather.create(intensity, duration)}: obtained from the pool, set up and added. The weather must be in
     * the world's content.
     */
    public static WeatherState create(World world, int weather, float intensity, float duration){
        var state = new WeatherState();
        boolean fresh = world.ids().free(Pooled.WEATHER_STATE) == 0;
        state.created(world, Pooled.WEATHER_STATE);
        // the constructor's field initializer: windVector = new Vec2().setToRandomDirection()
        if(fresh) state.windVector.setToRandomDirection(world.rand());
        state.intensity = Mathf.clamp(intensity);
        state.weather = weather;
        state.life = duration;
        state.add();
        return state;
    }

    public WeatherDef type(){
        return world().content().weather(weather);
    }

    /** The generated {@code add()}: {@code Groups.all}, {@code sync}, then {@code weather}. */
    @Override
    public void add(){
        if(isAdded()) return;
        EntityGroups groups = EntityGroups.of(world());
        if(groups != null){
            groups.addAll(this);
            groups.addSync(this);
        }
        Weathers weathers = Weathers.of(world());
        if(weathers != null) weathers.list().add(this);
        added(true);
    }

    /** The generated {@code remove()}: leaves the groups in the same order, then {@code Groups.queueFree}. */
    @Override
    public void remove(){
        if(!isAdded()) return;
        EntityGroups groups = EntityGroups.of(world());
        if(groups != null){
            groups.removeAll(this);
            groups.removeSync(this);
        }
        Weathers weathers = Weathers.of(world());
        if(weathers != null) weathers.list().remove(this);
        added(false);
        world().ids().queueFree(Pooled.WEATHER_STATE);
    }

    /**
     * {@code WeatherStateComp.update()}: fades, ages, then the weather's {@code update} (a particle weather's wind
     * pushes every unit) and {@code updateEffect} (its status, every {@code statusDuration - 5} ticks); removed once
     * its life is spent.
     */
    @Override
    public void update(){
        if(life < FADE_TIME){
            opacity = Math.min(life / FADE_TIME, opacity);
        }else{
            opacity = Mathf.lerpDelta(opacity, 1f, 0.004f);
        }
        life -= 1f;
        WeatherDef type = type();
        push(type);
        afflict(type);
        if(life < 0) remove();
    }

    /** {@code ParticleWeather.update}: the wind's impulse on every unit, when it is strong enough. */
    private void push(WeatherDef type){
        float speed = type.force() * intensity;
        if(speed > 0.001f){
            float windx = windVector.x * speed, windy = windVector.y * speed;
            ActiveList<Unit> units = world().units();
            for(int i = 0; i < units.size(); i++) units.get(i).impulse(windx, windy);
        }
    }

    /** {@code Weather.updateEffect}: the weather's status on every unit it targets. */
    private void afflict(WeatherDef type){
        StatusDef status = type.status();
        StatusTable table = StatusTable.of(world());
        if(status == null || status.id() == table.none()) return;
        if(effectTimer <= 0){
            effectTimer = type.statusDuration() - 5f;
            boolean air = type.statusAir(), ground = type.statusGround();
            world().units().each(unit -> {
                // UnitComp.checkTarget(statusAir, statusGround)
                if((unit.isGrounded() && ground) || (unit.isFlying() && air)) unit.statuses.apply(unit, status.id(), type.statusDuration());
            });
        }else{
            effectTimer -= 1f;
        }
    }

    @Override
    public Copyable blank(){
        return new WeatherState();
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (WeatherState)into;
        out.weatherIndex = weatherIndex;
        out.weather = weather;
        out.intensity = intensity;
        out.opacity = opacity;
        out.life = life;
        out.effectTimer = effectTimer;
        out.windVector = new Vec2(windVector);
        out.x = x;
        out.y = y;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.i(weather).f(intensity).f(opacity).f(life).f(effectTimer).f(windVector.x).f(windVector.y).f(x).f(y);
    }

    @Override
    public void write(CheckpointOut out){
        out.i("weather", weather).f("intensity", intensity).f("opacity", opacity).f("life", life).f("effectTimer", effectTimer)
            .obj("windVector", windVector).f("x", x).f("y", y);
    }

    @Override
    public void read(CheckpointIn in){
        weather = in.i("weather");
        intensity = in.f("intensity");
        opacity = in.f("opacity");
        life = in.f("life");
        effectTimer = in.f("effectTimer");
        windVector = in.obj("windVector", Vec2.class);
        x = in.f("x");
        y = in.f("y");
    }

    @Override
    public String toString(){
        return "WeatherState#" + id() + ":" + weather;
    }
}
