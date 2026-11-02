package com.xpdustry.shard.sim.combat;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.schema.*;
import com.xpdustry.shard.sim.bullet.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;
import java.util.function.*;

/**
 * The {@link Arms} of every unit type of a content epoch, by content id. Each weapon runs the class registered for
 * its kind with {@link WeaponKind}; a kind without one runs its nearest registered ancestor's class, or the base
 * {@code weapon}'s, and is {@link #unsupported() reported}. Immutable once built over a {@link BulletTypes}, whose
 * bullet types the weapons fire; the world's {@link Combat} part keeps one.
 */
public final class Armory{
    private static final String BASE = "weapon";

    private final BulletTypes bullets;
    private final Arms[] arms;
    private final List<String> unsupported;

    Armory(BulletTypes bullets){
        this.bullets = bullets;
        ContentEpoch content = bullets.content();
        int count = content.count(ContentType.UNIT);
        arms = new Arms[count];
        var missing = new TreeSet<String>();
        for(int id = 0; id < count; id++){
            UnitDef type = content.unit(id);
            if(!type.baked()){
                arms[id] = Arms.none();
                continue;
            }
            List<WeaponDef> defs = type.weapons();
            var weapons = new Weapon[defs.size()];
            for(int i = 0; i < weapons.length; i++){
                WeaponDef def = defs.get(i);
                weapons[i] = instantiate(def, missing);
                weapons[i].load(def, i, bullets);
            }
            arms[id] = new Arms(type, weapons);
        }
        unsupported = List.copyOf(missing);
    }

    /** The armory of {@code world}: its {@link Combat} part's, else one built for the call. */
    public static Armory of(World world){
        Combat combat = world.find(Combat.class);
        return combat == null ? new Armory(world.bullets().types(world)) : combat.armory(world);
    }

    /** The bullet types its weapons fire. */
    public BulletTypes bullets(){
        return bullets;
    }

    /** The arms of unit type {@code type}. */
    public Arms arms(UnitDef type){
        return arms[type.id()];
    }

    /** The weapon kinds that run an ancestor's behaviour because none of their own is ported, sorted. */
    public List<String> unsupported(){
        return unsupported;
    }

    private static Weapon instantiate(WeaponDef def, Set<String> missing){
        KindSchema kind = def.fields().kind();
        for(KindSchema at = kind; at != null; at = at.parent()){
            Supplier<? extends Weapon> constructor = Registered.KINDS.get(at.key());
            if(constructor == null && !at.key().equals(BASE)) continue;
            if(at != kind) missing.add(kind.key());
            Weapon weapon = constructor == null ? new Weapon() : constructor.get();
            if(at != kind) weapon.unsupported();
            return weapon;
        }
        missing.add(kind.key());
        Weapon weapon = new Weapon();
        weapon.unsupported();
        return weapon;
    }

    private static final class Registered{
        static final SortedMap<String, Supplier<? extends Weapon>> KINDS = new TreeMap<>();

        static{
            for(Class<? extends Weapon> type : Registries.classes(Weapon.class)){
                String kind = Registries.key(type, WeaponKind.class);
                if(kind == null) throw new IllegalArgumentException(type.getName() + " has no @WeaponKind");
                var previous = KINDS.put(kind, Registries.constructor(type));
                if(previous != null) throw new IllegalArgumentException("weapon kind " + kind + " is registered twice");
            }
        }

        private Registered(){
        }
    }
}
