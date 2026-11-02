package com.xpdustry.shard.sim.unit.ability;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.schema.*;
import com.xpdustry.shard.sim.unit.*;

import java.util.*;
import java.util.function.*;

/**
 * The abilities of one unit type, in vanilla's order, and the three places the unit runs them: {@code created},
 * {@code update} and {@code death}, each in the type's order on the unit's own states ({@link Unit#abilities}).
 *
 * <p>Each ability runs the class registered for its kind with {@link AbilityKind}; a kind without one runs its nearest
 * registered ancestor's class, or the base {@code ability}'s (nothing), and is {@link #unsupported() reported}.
 * Immutable once built; a world's {@link UnitTable} keeps one per type.
 */
public final class Abilities{
    private static final String BASE = "ability";
    private static final Abilities NONE = new Abilities(new Ability[0], List.of());

    private final Ability[] abilities;
    private final List<String> unsupported;

    private Abilities(Ability[] abilities, List<String> unsupported){
        this.abilities = abilities;
        this.unsupported = unsupported;
    }

    /** The abilities of {@code type}, loaded against {@code content}; none for a type without baked fields. */
    public static Abilities of(ContentEpoch content, UnitDef type){
        if(!type.baked()) return NONE;
        List<AbilityDef> defs = type.abilityDefs();
        if(defs.isEmpty()) return NONE;
        var missing = new TreeSet<String>();
        var abilities = new Ability[defs.size()];
        for(int i = 0; i < abilities.length; i++){
            AbilityDef def = defs.get(i);
            abilities[i] = instantiate(def, missing);
            abilities[i].load(def, i, content);
        }
        return new Abilities(abilities, List.copyOf(missing));
    }

    /** The abilities of {@code unit}'s type. */
    public static Abilities of(Unit unit){
        return unit.world().unitTable().abilities(unit.type());
    }

    public int size(){
        return abilities.length;
    }

    public Ability get(int index){
        return abilities[index];
    }

    /** The ability kinds that run an ancestor's behaviour because none of their own is ported, sorted. */
    public List<String> unsupported(){
        return unsupported;
    }

    /** One fresh state per ability, for a unit taking this type ({@code setType}). */
    public UnitAbility[] create(){
        var out = new UnitAbility[abilities.length];
        for(int i = 0; i < out.length; i++) out[i] = abilities[i].create();
        return out;
    }

    /**
     * {@code for(Ability ab : type.abilities) ab.created(unit)} in {@code UnitType.create}. The unit's abilities are
     * vanilla's copies, made before the type's {@code created} runs on them.
     */
    public static void created(Unit unit){
        Abilities kinds = of(unit);
        for(int i = 0; i < unit.abilities.length && i < kinds.abilities.length; i++) kinds.abilities[i].created(unit, unit.abilities[i]);
    }

    /** {@code for(Ability a : abilities) a.update(self())} (UnitComp.java:776). */
    public static void update(Unit unit){
        Abilities kinds = of(unit);
        for(int i = 0; i < unit.abilities.length && i < kinds.abilities.length; i++) kinds.abilities[i].update(unit, unit.abilities[i]);
    }

    /** {@code for(Ability a : abilities) a.death(self())} (UnitComp.java:942). */
    public static void death(Unit unit){
        Abilities kinds = of(unit);
        for(int i = 0; i < unit.abilities.length && i < kinds.abilities.length; i++) kinds.abilities[i].death(unit, unit.abilities[i]);
    }

    private static Ability instantiate(AbilityDef def, Set<String> missing){
        KindSchema kind = def.fields().kind();
        for(KindSchema at = kind; at != null; at = at.parent()){
            Supplier<? extends Ability> constructor = Registered.KINDS.get(at.key());
            if(constructor == null && !at.key().equals(BASE)) continue;
            if(at != kind) missing.add(kind.key());
            Ability ability = constructor == null ? new Ability() : constructor.get();
            if(at != kind) ability.unsupported();
            return ability;
        }
        missing.add(kind.key());
        var ability = new Ability();
        ability.unsupported();
        return ability;
    }

    private static final class Registered{
        static final SortedMap<String, Supplier<? extends Ability>> KINDS = new TreeMap<>();

        static{
            for(Class<? extends Ability> type : Registries.classes(Ability.class)){
                String kind = Registries.key(type, AbilityKind.class);
                if(kind == null) throw new IllegalArgumentException(type.getName() + " has no @AbilityKind");
                var previous = KINDS.put(kind, Registries.constructor(type));
                if(previous != null) throw new IllegalArgumentException("ability kind " + kind + " is registered twice");
            }
        }

        private Registered(){
        }
    }
}
