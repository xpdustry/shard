package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.unit.ability.*;
import com.xpdustry.shard.sim.world.*;

/**
 * One ability's state on one unit (vanilla's per-unit {@code Ability} copy), owned by its {@link Unit}. Only
 * {@link #data} is synced and saved ({@code TypeIO.writeAbilities}). The type's {@link Ability} at the same index says
 * what it does and makes the state ({@link Ability#create()}): this class for the kinds that keep nothing but
 * {@code data}, which is also what vanilla's {@code EmptyDataAbility} placeholder holds, and a subclass for those that
 * keep more (timers, a shield's radius…), each overriding {@link #copy()}, {@link #hash(Hasher)} and the checkpoint
 * methods.
 *
 * <p>{@link Persistence#AUTHORITATIVE}.
 */
public class UnitAbility implements Checkpointable{
    public float data;

    public UnitAbility(){
    }

    /** A copy for a fork; subclasses copy their own state too ({@link #copyInto}). */
    public UnitAbility copy(){
        var out = new UnitAbility();
        out.data = data;
        return out;
    }

    /** Copies this state's {@link #data} into {@code out}, a new state of the same class, and returns it. */
    protected final <T extends UnitAbility> T copyInto(T out){
        out.data = data;
        return out;
    }

    public void hash(Hasher hasher){
        hasher.f(data);
    }

    /** Subclasses store their own state after calling this. */
    @Override
    public void write(CheckpointOut out){
        out.f("data", data);
    }

    @Override
    public void read(CheckpointIn in){
        data = in.f("data");
    }
}
