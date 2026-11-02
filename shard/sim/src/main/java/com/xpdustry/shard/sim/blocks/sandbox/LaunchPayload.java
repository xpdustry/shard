package com.xpdustry.shard.sim.blocks.sandbox;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code LaunchPayload} entity (LaunchPad.java:227, {@code LaunchPayloadComp} with {@code TimedComp}): the
 * pod a launch pad sends up, which flies for its {@link #lifetime} and is gone. Off the campaign it carries its items
 * nowhere ({@code remove()} returns at once), but it is a saved entity of {@code Groups.all}, updated from
 * {@code MISC}, and its smoke draws two values from the random every {@code 4 - 2 fin} ticks.
 */
public final class LaunchPayload extends Entity implements Checkpointable{
    public float x, y;
    public int team;
    public float time, lifetime;
    /** {@code stacks}, as item id and amount pairs. */
    public int[] stacks = new int[0];
    /** The smoke's {@code Interval} ({@code in}): when it last fired, in {@link Timers#time()}. */
    public float in;

    /** A blank payload for a checkpoint, a fork or a save to fill. */
    public LaunchPayload(){
    }

    /** {@code LaunchPayload.create()}: a new payload with a fresh id, not added. */
    public static LaunchPayload create(World world){
        var payload = new LaunchPayload();
        payload.created(world);
        return payload;
    }

    /** A payload read from a save under its saved id, not added. */
    public static LaunchPayload read(World world, int id){
        var payload = new LaunchPayload();
        payload.created(world, id);
        return payload;
    }

    /** {@code fin()}. */
    public float fin(){
        return time / lifetime;
    }

    /** The generated {@code update()}: the smoke, then {@code TimedComp}'s ageing. */
    @Override
    public void update(){
        float r = 3f;
        // in.get(4 - fin * 2): Fx.rocketSmoke at a jittered point, drawn on a server too
        float now = world().timers().time();
        float interval = 4f - fin() * 2f;
        if(now - in >= interval || now < in){
            in = now;
            Mathf.range(world().rand(), r);
            Mathf.range(world().rand(), r);
        }
        time = Math.min(time + 1f, lifetime);
        if(time >= lifetime) remove();
    }

    /** The generated {@code add()}: {@code Groups.all} (and {@code draw}, which a server doesn't keep). */
    @Override
    public void add(){
        if(isAdded()) return;
        EntityGroups groups = EntityGroups.of(world());
        if(groups != null) groups.addAll(this);
        added(true);
    }

    /** The generated {@code remove()}; off the campaign, {@code LaunchPayloadComp.remove} does nothing. */
    @Override
    public void remove(){
        if(!isAdded()) return;
        EntityGroups groups = EntityGroups.of(world());
        if(groups != null) groups.removeAll(this);
        added(false);
    }

    @Override
    public Copyable blank(){
        return new LaunchPayload();
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (LaunchPayload)into;
        out.x = x;
        out.y = y;
        out.team = team;
        out.time = time;
        out.lifetime = lifetime;
        out.stacks = stacks.clone();
        out.in = in;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.f(x).f(y).i(team).f(time).f(lifetime).ints(stacks).f(in);
    }

    @Override
    public void write(CheckpointOut out){
        out.f("x", x).f("y", y).i("team", team).f("time", time).f("lifetime", lifetime).ints("stacks", stacks).f("in", in);
    }

    @Override
    public void read(CheckpointIn in){
        x = in.f("x");
        y = in.f("y");
        team = in.i("team");
        time = in.f("time");
        lifetime = in.f("lifetime");
        stacks = in.ints("stacks");
        this.in = in.f("in");
    }

    @Override
    public String toString(){
        return "LaunchPayload#" + id();
    }
}
