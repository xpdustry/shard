package com.xpdustry.shard.compat.v160.entity;

import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.wire.*;
import com.xpdustry.shard.wire.v160_7.*;

import java.util.*;

/**
 * One unit as the generated encoders read it, read live from the {@link Unit} instead of frozen: the source of
 * {@link UnitProjection}'s values, and a reusable, non-escaping view for an encoder that writes on the world thread at
 * once ({@code EntitySnapshots}, W4.6). Bind it, encode, and drop it: what it returns (its lists and arrays included)
 * is valid only until the next {@link #bind}, and only while the world stands still. Anything that keeps a unit's
 * values or encodes elsewhere takes a {@link UnitProjection}.
 *
 * <p>Reading is pure: no getter changes the unit or the world.
 */
public final class UnitView implements UnitEntityLayout.Source, MechUnitLayout.Source, PayloadUnitLayout.Source,
    BuildingTetherPayloadUnitLayout.Source, TimedKillUnitLayout.Source, TargetDummyUnitLayout.Source{
    /** {@code ItemStack}'s empty stack, as Shard keeps it (0 copper), shared. */
    static final Codecs.ItemStack EMPTY_STACK = new Codecs.ItemStack(0, 0);
    private static final float[] NO_ABILITIES = {};

    private Unit unit;
    private TileGrid tiles;
    private int classId;
    private List<Codecs.Payload> payloads = List.of();
    /** Reused arrays, by length, for {@link #abilities()}. */
    private float[][] abilities = new float[4][];
    private final ArrayList<Codecs.Mount> mounts = new ArrayList<>();
    private final ArrayList<Codecs.StatusEntry> statuses = new ArrayList<>();
    private final ArrayList<Codecs.Plan> plans = new ArrayList<>();
    /** The unit {@link #statuses} were filled for: the layouts read them twice (their size, then each). */
    private Unit statusesOf;

    /** This view, now of {@code unit}, encoded as class {@code classId}, carrying {@code payloads}. */
    public UnitView bind(Unit unit, int classId, List<Codecs.Payload> payloads){
        this.unit = unit;
        this.tiles = unit.world().tiles();
        this.classId = classId;
        this.payloads = payloads;
        this.statusesOf = null;
        return this;
    }

    /** The unit's entity id. */
    public int id(){
        return unit.id();
    }

    /** The entity class whose layout encodes this unit. */
    public int classId(){
        return classId;
    }

    /** Each ability's {@code data}, in a reused array of exactly their count. */
    @Override
    public float[] abilities(){
        int n = unit.abilities.length;
        if(n == 0) return NO_ABILITIES;
        if(n >= abilities.length) abilities = Arrays.copyOf(abilities, n + 1);
        float[] data = abilities[n];
        if(data == null) abilities[n] = data = new float[n];
        for(int i = 0; i < n; i++) data[i] = unit.abilities[i].data;
        return data;
    }

    @Override
    public float aimX(){
        return unit.aimX;
    }

    @Override
    public float aimY(){
        return unit.aimY;
    }

    @Override
    public Codecs.Controller controller(){
        return WireControllers.of(unit.controller, tiles);
    }

    @Override
    public float elevation(){
        return unit.elevation;
    }

    @Override
    public double flag(){
        return unit.flag;
    }

    @Override
    public float health(){
        return unit.health;
    }

    @Override
    public boolean isShooting(){
        return unit.isShooting;
    }

    /** Kept packed already. */
    @Override
    public int mineTile(){
        return unit.mineTile;
    }

    /** Each mount's shoot and rotate bits and aim. */
    @Override
    public List<Codecs.Mount> mounts(){
        mounts.clear();
        for(WeaponMount mount : unit.mounts){
            mounts.add(new Codecs.Mount((mount.shoot ? 1 : 0) | (mount.rotate ? 2 : 0), mount.aimX, mount.aimY));
        }
        return mounts;
    }

    @Override
    public List<Codecs.Plan> plans(){
        if(unit.plans.isEmpty()) return Collections.emptyList();
        plans.clear();
        var ids = WirePlans.ContentIds.of(unit.world().content());
        for(int i = 0; i < unit.plans.size(); i++) plans.add(WirePlans.of(unit.plans.get(i), ids));
        return plans;
    }

    @Override
    public float rotation(){
        return unit.rotation;
    }

    @Override
    public float shield(){
        return unit.shield;
    }

    @Override
    public boolean spawnedByCore(){
        return unit.spawnedByCore;
    }

    /** {@code stackItem}/{@code stackAmount}; an empty stack is 0 copper, as vanilla's. */
    @Override
    public Codecs.ItemStack stack(){
        return unit.stackItem == 0 && unit.stackAmount == 0 ? EMPTY_STACK : new Codecs.ItemStack(unit.stackItem, unit.stackAmount);
    }

    /** Empty lists are {@link Collections#emptyList()}, whose iterator is shared: most units have no statuses. */
    @Override
    public List<Codecs.StatusEntry> statuses(){
        if(unit.statuses.size() == 0) return Collections.emptyList();
        if(statusesOf == unit) return statuses;
        statusesOf = unit;
        statuses.clear();
        for(int i = 0; i < unit.statuses.size(); i++){
            StatusEntry entry = unit.statuses.get(i);
            statuses.add(new Codecs.StatusEntry(entry.effect, entry.time, entry.damageMultiplier, entry.healthMultiplier,
                entry.speedMultiplier, entry.reloadMultiplier, entry.buildSpeedMultiplier, entry.dragMultiplier, entry.armorOverride));
        }
        return statuses;
    }

    @Override
    public int team(){
        return unit.team;
    }

    @Override
    public short type(){
        return (short)unit.type().id();
    }

    @Override
    public boolean updateBuilding(){
        return unit.updateBuilding;
    }

    @Override
    public Codecs.Vec2 vel(){
        return new Codecs.Vec2(unit.vel.x, unit.vel.y);
    }

    @Override
    public float x(){
        return unit.x;
    }

    @Override
    public float y(){
        return unit.y;
    }

    /** A mech's base rotation lives on its {@link MoveShape.Mech}; 0 for any other unit. */
    @Override
    public float baseRotation(){
        return unit.shape instanceof MoveShape.Mech mech ? mech.baseRotation : 0f;
    }

    @Override
    public List<Codecs.Payload> payloads(){
        return payloads;
    }

    /** The tether's building position, or {@link Codecs#NONE}. */
    @Override
    public int building(){
        return unit.tether == null ? Codecs.NONE : tiles.toWire(unit.tether.tile());
    }

    @Override
    public float lifetime(){
        return unit.lifetime;
    }

    @Override
    public float time(){
        return unit.time;
    }
}
