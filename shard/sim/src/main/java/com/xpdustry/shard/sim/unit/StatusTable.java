package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * The world's status effects, read once from its content (WP42): each effect's stats, the transitions of
 * {@code StatusEffect.applyTransition}, each unit type's immunities and each floor's status. Vanilla's transitions are
 * lambdas built in {@code StatusEffects.load}: the opposites come from the content ({@link StatusDef#opposites()}),
 * and the six affinity reactions, which data can't express, are ported here by name ({@link Reaction}).
 *
 * <p>{@link Persistence#DERIVED}: immutable, rebuilt from the content. Forks share its arrays.
 */
@Register
public final class StatusTable implements WorldPart{
    /** No transition: {@code transitions.get(to)} is null. */
    public static final byte NONE = 0, OPPOSITE = 1;

    @Derived("rebuild: the world's content")
    private Table table = Table.EMPTY;

    public StatusTable(){
    }

    private StatusTable(Table table){
        this.table = table;
    }

    @Override
    public void init(World world){
        table = Table.of(world.content());
    }

    @Override
    public void rebuild(World world){
        init(world);
    }

    @Override
    public Persistence persistence(){
        return Persistence.DERIVED;
    }

    @Override
    public WorldPart copy(CopyContext context){
        return new StatusTable(table);
    }

    @Override
    public void hash(Hasher hasher){
    }

    /** The world's table, or an empty one in a world built without it. */
    public static StatusTable of(World world){
        StatusTable statuses = world.find(StatusTable.class);
        return statuses == null ? EMPTY_PART : statuses;
    }

    private static final StatusTable EMPTY_PART = new StatusTable(Table.EMPTY);

    /** How many status effects the content has. */
    public int size(){
        return table.size;
    }

    /** Whether {@code effect} is a status id of the content: vanilla's null otherwise. */
    public boolean exists(int effect){
        return effect >= 0 && effect < table.size;
    }

    /** The id of the status named {@code name}, or -1. */
    public int id(String name){
        return table.ids.getOrDefault(name, -1);
    }

    /** {@code StatusEffects.none}'s id, or -1. */
    public int none(){
        return table.none;
    }

    /** {@code StatusEffects.boss}'s id, or -1. */
    public int boss(){
        return table.boss;
    }

    /** {@code StatusEffects.dynamic}'s id, or -1. */
    public int dynamicEffect(){
        return table.dynamic;
    }

    public float damage(int effect){
        return table.damage[effect];
    }

    public float damageMultiplier(int effect){
        return table.damageMultiplier[effect];
    }

    public float healthMultiplier(int effect){
        return table.healthMultiplier[effect];
    }

    public float speedMultiplier(int effect){
        return table.speedMultiplier[effect];
    }

    public float reloadMultiplier(int effect){
        return table.reloadMultiplier[effect];
    }

    public float buildSpeedMultiplier(int effect){
        return table.buildSpeedMultiplier[effect];
    }

    public float dragMultiplier(int effect){
        return table.dragMultiplier[effect];
    }

    public float transitionDamage(int effect){
        return table.transitionDamage[effect];
    }

    public float intervalDamageTime(int effect){
        return table.intervalDamageTime[effect];
    }

    public float intervalDamage(int effect){
        return table.intervalDamage[effect];
    }

    public boolean intervalDamagePierce(int effect){
        return table.flags[effect].get(Table.INTERVAL_PIERCE);
    }

    public boolean disarm(int effect){
        return table.flags[effect].get(Table.DISARM);
    }

    public boolean permanent(int effect){
        return table.flags[effect].get(Table.PERMANENT);
    }

    public boolean reactive(int effect){
        return table.flags[effect].get(Table.REACTIVE);
    }

    public boolean dynamic(int effect){
        return table.flags[effect].get(Table.DYNAMIC);
    }

    /**
     * What applying {@code to} does to an entry of {@code from}: {@link #NONE}, {@link #OPPOSITE}, or a
     * {@link Reaction}'s code.
     */
    public byte transition(int from, int to){
        return table.transitions[from * table.size + to];
    }

    /** {@code type.immunities.contains(effect)}. */
    public boolean immune(UnitDef type, int effect){
        Bits immunities = type.id() < table.immunities.length ? table.immunities[type.id()] : null;
        return immunities != null && immunities.get(effect);
    }

    /** {@code floor.status}'s id for block {@code floor}, or -1 when it isn't a floor (vanilla's {@code none}). */
    public int floorStatus(int floor){
        return floor >= 0 && floor < table.floorStatus.length ? table.floorStatus[floor] : -1;
    }

    public float floorStatusDuration(int floor){
        return floor >= 0 && floor < table.floorStatusDuration.length ? table.floorStatusDuration[floor] : 0f;
    }

    /**
     * The affinity reactions of {@code StatusEffects.load}, keyed by the effect whose entry reacts and the effect
     * applied to it. Each is a lambda in vanilla.
     */
    public enum Reaction{
        /** {@code burning} meets {@code tarred}: pierces {@code transitionDamage}, a fire effect, burns longer (at most 300). */
        BURNING_TARRED("burning", "tarred"),
        /** {@code freezing} meets {@code blasted}: pierces {@code transitionDamage}. */
        FREEZING_BLASTED("freezing", "blasted"),
        /** {@code wet} meets {@code shocked}: {@code transitionDamage} through armor. */
        WET_SHOCKED("wet", "shocked"),
        /** {@code melting} meets {@code tarred}: pierces 8, a fire effect, melts longer (at most 200). */
        MELTING_TARRED("melting", "tarred"),
        /** {@code tarred} meets {@code melting}: becomes melting for both durations. */
        TARRED_MELTING("tarred", "melting"),
        /** {@code tarred} meets {@code burning}: becomes burning for both durations. */
        TARRED_BURNING("tarred", "burning");

        final String from, to;

        Reaction(String from, String to){
            this.from = from;
            this.to = to;
        }

        /** The transition code of this reaction. */
        public byte code(){
            return (byte)(ordinal() + 2);
        }

        /** The reaction with transition code {@code code}. */
        public static Reaction of(byte code){
            return values()[code - 2];
        }
    }

    private record Table(int size, TreeMap<String, Integer> ids, int none, int boss, int dynamic, float[] damage,
                         float[] damageMultiplier, float[] healthMultiplier, float[] speedMultiplier, float[] reloadMultiplier,
                         float[] buildSpeedMultiplier, float[] dragMultiplier, float[] transitionDamage, float[] intervalDamageTime,
                         float[] intervalDamage, Bits[] flags, byte[] transitions, Bits[] immunities, int[] floorStatus,
                         float[] floorStatusDuration){
        static final int INTERVAL_PIERCE = 0, DISARM = 1, PERMANENT = 2, REACTIVE = 3, DYNAMIC = 4;
        static final Table EMPTY = new Table(0, new TreeMap<>(), -1, -1, -1, new float[0], new float[0], new float[0], new float[0],
            new float[0], new float[0], new float[0], new float[0], new float[0], new float[0], new Bits[0], new byte[0], new Bits[0],
            new int[0], new float[0]);

        static Table of(ContentEpoch content){
            int size = content.count(ContentType.STATUS);
            var ids = new TreeMap<String, Integer>();
            float[] damage = new float[size], damageMultiplier = new float[size], healthMultiplier = new float[size];
            float[] speedMultiplier = new float[size], reloadMultiplier = new float[size], buildSpeedMultiplier = new float[size];
            float[] dragMultiplier = new float[size], transitionDamage = new float[size], intervalDamageTime = new float[size];
            float[] intervalDamage = new float[size];
            var flags = new Bits[size];
            var transitions = new byte[size * size];
            for(int id = 0; id < size; id++){
                StatusDef status = content.status(id);
                ids.put(status.name(), id);
                damage[id] = status.damage();
                damageMultiplier[id] = status.damageMultiplier();
                healthMultiplier[id] = status.healthMultiplier();
                speedMultiplier[id] = status.speedMultiplier();
                reloadMultiplier[id] = status.reloadMultiplier();
                buildSpeedMultiplier[id] = status.buildSpeedMultiplier();
                dragMultiplier[id] = status.dragMultiplier();
                transitionDamage[id] = status.transitionDamage();
                intervalDamageTime[id] = status.intervalDamageTime();
                intervalDamage[id] = status.intervalDamage();
                var bits = new Bits(5);
                bits.set(INTERVAL_PIERCE, status.intervalDamagePierce());
                bits.set(DISARM, status.disarm());
                bits.set(PERMANENT, status.permanent());
                bits.set(REACTIVE, status.reactive());
                bits.set(DYNAMIC, status.dynamic());
                flags[id] = bits;
                // handleOpposite puts both directions; affinities without a reaction below have no handler
                for(StatusDef other : status.opposites()) transitions[id * size + other.id()] = OPPOSITE;
            }
            for(Reaction reaction : Reaction.values()){
                Integer from = ids.get(reaction.from), to = ids.get(reaction.to);
                if(from != null && to != null) transitions[from * size + to] = reaction.code();
            }

            var immunities = new Bits[content.units()];
            for(int id = 0; id < immunities.length; id++){
                UnitDef type = content.unit(id);
                if(!type.baked()) continue;
                for(String name : type.immunities()){
                    Integer status = ids.get(name);
                    if(status == null) continue;
                    if(immunities[id] == null) immunities[id] = new Bits(size);
                    immunities[id].set(status);
                }
            }

            int[] floorStatus = new int[content.blocks()];
            float[] floorStatusDuration = new float[content.blocks()];
            Arrays.fill(floorStatus, -1);
            for(int id = 0; id < floorStatus.length; id++){
                if(!(content.block(id) instanceof FloorDef floor) || !floor.baked()) continue;
                StatusDef status = floor.status();
                floorStatus[id] = status == null ? -1 : status.id();
                floorStatusDuration[id] = floor.statusDuration();
            }
            return new Table(size, ids, ids.getOrDefault("none", -1), ids.getOrDefault("boss", -1), ids.getOrDefault("dynamic", -1),
                damage, damageMultiplier, healthMultiplier, speedMultiplier, reloadMultiplier, buildSpeedMultiplier, dragMultiplier,
                transitionDamage, intervalDamageTime, intervalDamage, flags, transitions, immunities, floorStatus, floorStatusDuration);
        }
    }
}
