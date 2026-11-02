package com.xpdustry.shard.sim.unit.ability;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.unit.*;

/**
 * Vanilla's {@code Ability} (Ability.java): one ability of a unit type, its parameters read once from the content's
 * {@link AbilityDef}, and what it does to a unit that carries it. Vanilla gives each unit a {@code copy()} of its
 * type's abilities, parameters and state together; Shard splits them, as {@code Weapon} and {@code WeaponMount} are
 * split: this object is the type's and immutable once loaded, and each unit holds a {@link UnitAbility} per ability,
 * made by {@link #create()}, with the state vanilla's copy keeps ({@code data} and, in a subclass, timers and the
 * like).
 *
 * <p>This class is the {@code ability} kind, which does nothing; the kinds with behaviour of their own are subclasses
 * registered with {@link AbilityKind}. Every random draw vanilla's server makes is kept, sound pitches included;
 * effects, sounds and drawing are not (a headless {@code Effect.at} draws nothing).
 */
@Registry
public class Ability{
    private AbilityDef def;
    private int index;
    private boolean supported = true;

    /** For {@link Abilities}, which then {@link #load loads} it. */
    public Ability(){
    }

    /** Reads the parameters of ability {@code index} of a unit type from {@code def}. Subclasses read theirs after this. */
    protected void load(AbilityDef def, int index, ContentEpoch content){
        this.def = def;
        this.index = index;
    }

    /** Marks this ability as standing in for a kind with no behaviour of its own. */
    final void unsupported(){
        supported = false;
    }

    /** Whether its kind is ported, rather than run as a registered ancestor's. */
    public final boolean supported(){
        return supported;
    }

    public final AbilityDef def(){
        return def;
    }

    /** Its index in the type's abilities, which is its state's index in {@link Unit#abilities}. */
    public final int index(){
        return index;
    }

    /** A unit's fresh copy of this ability: vanilla's {@code type.abilities.get(i).copy()} in {@code setType}. */
    public UnitAbility create(){
        return new UnitAbility();
    }

    /** {@code created(unit)}: {@code UnitType.create} made the unit. */
    public void created(Unit unit, UnitAbility state){
    }

    /** {@code update(unit)}: each tick, from {@code UnitComp.update}. */
    public void update(Unit unit, UnitAbility state){
    }

    /** {@code death(unit)}: from {@code UnitComp.destroy}, after the crash damage and before the removal. */
    public void death(Unit unit, UnitAbility state){
    }

    @Override
    public String toString(){
        return def == null ? getClass().getSimpleName() : def.kind().name() + "#" + index;
    }
}
