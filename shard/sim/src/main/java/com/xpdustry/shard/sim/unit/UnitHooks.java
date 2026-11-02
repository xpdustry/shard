package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.sim.unit.ability.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Ordered seams in the unit core for behaviour other packages port, called where vanilla's {@code UnitComp} and the
 * generated unit classes run it, so their random draws and Calls fall in vanilla's order:
 * <ul>
 *   <li>{@link #updateAbilities}: {@code for(Ability a : abilities) a.update(self())} in {@code UnitComp.update}
 *   (UnitComp.java:776), from {@link UnitCoreComponent} after the environment check and before drag and falling;</li>
 *   <li>{@link #destroyed}: the death weapons of {@code UnitComp.destroy} (UnitComp.java:911), from
 *   {@link Unit#destroy()} after the death explosion and {@code UnitDestroyEvent}, before the crash damage
 *   (weapons, WP43);</li>
 *   <li>{@link #abilitiesDied}: {@code for(Ability a : abilities) a.death(self())} (UnitComp.java:942), from
 *   {@link Unit#destroy()} after the crash damage, before the removal;</li>
 *   <li>{@link #removed}: {@code WeaponsComp.remove}, last in the generated {@code remove()}, from
 *   {@link Unit#remove()} (weapons, WP43).</li>
 * </ul>
 * A world finds them as the first {@link WorldPart} implementing this interface, the combat part; without one, they
 * do nothing ({@link #NONE}), but the abilities' bodies, which are the defaults ({@link Abilities}), still run.
 */
public interface UnitHooks{
    UnitHooks NONE = new UnitHooks(){
    };

    /** The unit's abilities update. */
    default void updateAbilities(Unit unit){
        Abilities.update(unit);
    }

    /** The unit's abilities saw it die: its crash damage is done, its removal is next. */
    default void abilitiesDied(Unit unit){
        Abilities.death(unit);
    }

    /** The unit is being destroyed: its death explosion is done, its crash damage and removal are next. */
    default void destroyed(Unit unit){
    }

    /** The unit left the world. */
    default void removed(Unit unit){
    }

    static UnitHooks of(World world){
        UnitHooks hooks = world.find(UnitHooks.class);
        return hooks == null ? NONE : hooks;
    }
}
