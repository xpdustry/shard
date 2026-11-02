package com.xpdustry.shard.compat.v160.entity;

import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.wire.*;
import com.xpdustry.shard.wire.v160_7.*;

import java.util.*;

/**
 * One unit, frozen for the generated encoders: every field any of the six unit layouts reads, captured on the world
 * thread ({@link EntityProjector#unit}) and safe to keep and encode from any thread. The values are a
 * {@link UnitView}'s, copied; an encoder that writes at once on the world thread can take the view itself. One class serves every unit layout; which
 * one applies is {@link #classId()}'s ({@link EntityClasses#writeSync}). Fields a layout lacks are captured anyway and
 * left unread.
 *
 * <p>Where vanilla's values come from in Shard's {@link Unit}: the item stack is {@code stackItem}/{@code stackAmount}
 * (an empty stack is 0 copper, as vanilla's); {@code mineTile} is kept packed already; mounts carry their shoot and
 * rotate bits; abilities their {@code data}; the tether its building's position, or -1; a mech's base rotation lives
 * on its {@link MoveShape.Mech}.
 */
public final class UnitProjection implements UnitEntityLayout.Source, MechUnitLayout.Source, PayloadUnitLayout.Source,
    BuildingTetherPayloadUnitLayout.Source, TimedKillUnitLayout.Source, TargetDummyUnitLayout.Source{
    private final int id, classId;
    private final float[] abilities;
    private final float aimX, aimY, elevation, health, rotation, shield, x, y, baseRotation, lifetime, time;
    private final Codecs.Controller controller;
    private final double flag;
    private final boolean isShooting, spawnedByCore, updateBuilding;
    private final int mineTile, team, building;
    private final short type;
    private final List<Codecs.Mount> mounts;
    private final List<Codecs.Plan> plans;
    private final List<Codecs.StatusEntry> statuses;
    private final List<Codecs.Payload> payloads;
    private final Codecs.ItemStack stack;
    private final Codecs.Vec2 vel;

    /** Freezes what {@code live} reads now: a copy of every value, its lists immutable. */
    UnitProjection(UnitView live){
        id = live.id();
        classId = live.classId();
        float[] data = live.abilities();
        abilities = data.length == 0 ? data : data.clone();
        aimX = live.aimX();
        aimY = live.aimY();
        controller = live.controller();
        elevation = live.elevation();
        flag = live.flag();
        health = live.health();
        isShooting = live.isShooting();
        mineTile = live.mineTile();
        mounts = List.copyOf(live.mounts());
        plans = List.copyOf(live.plans());
        rotation = live.rotation();
        shield = live.shield();
        spawnedByCore = live.spawnedByCore();
        stack = live.stack();
        statuses = List.copyOf(live.statuses());
        team = live.team();
        type = live.type();
        updateBuilding = live.updateBuilding();
        vel = live.vel();
        x = live.x();
        y = live.y();
        baseRotation = live.baseRotation();
        payloads = List.copyOf(live.payloads());
        building = live.building();
        lifetime = live.lifetime();
        time = live.time();
    }

    /** The unit's entity id. */
    public int id(){
        return id;
    }

    /** The entity class whose layout encodes this unit. */
    public int classId(){
        return classId;
    }

    @Override
    public float[] abilities(){
        return abilities;
    }

    @Override
    public float aimX(){
        return aimX;
    }

    @Override
    public float aimY(){
        return aimY;
    }

    @Override
    public Codecs.Controller controller(){
        return controller;
    }

    @Override
    public float elevation(){
        return elevation;
    }

    @Override
    public double flag(){
        return flag;
    }

    @Override
    public float health(){
        return health;
    }

    @Override
    public boolean isShooting(){
        return isShooting;
    }

    @Override
    public int mineTile(){
        return mineTile;
    }

    @Override
    public List<Codecs.Mount> mounts(){
        return mounts;
    }

    @Override
    public List<Codecs.Plan> plans(){
        return plans;
    }

    @Override
    public float rotation(){
        return rotation;
    }

    @Override
    public float shield(){
        return shield;
    }

    @Override
    public boolean spawnedByCore(){
        return spawnedByCore;
    }

    @Override
    public Codecs.ItemStack stack(){
        return stack;
    }

    @Override
    public List<Codecs.StatusEntry> statuses(){
        return statuses;
    }

    @Override
    public int team(){
        return team;
    }

    @Override
    public short type(){
        return type;
    }

    @Override
    public boolean updateBuilding(){
        return updateBuilding;
    }

    @Override
    public Codecs.Vec2 vel(){
        return vel;
    }

    @Override
    public float x(){
        return x;
    }

    @Override
    public float y(){
        return y;
    }

    @Override
    public float baseRotation(){
        return baseRotation;
    }

    @Override
    public List<Codecs.Payload> payloads(){
        return payloads;
    }

    @Override
    public int building(){
        return building;
    }

    @Override
    public float lifetime(){
        return lifetime;
    }

    @Override
    public float time(){
        return time;
    }
}
