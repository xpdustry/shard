package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.sim.world.*;

/**
 * The area damage of a unit's death (WP41), from {@code UnitComp.destroy}: {@code Damage.dynamicExplosion} from the
 * carried items, and the crash damage of a flying wreck. A world finds it as the first {@link WorldPart} implementing
 * it; without one, deaths hurt nothing around them ({@link #NONE}).
 */
public interface UnitDamage{
    UnitDamage NONE = new UnitDamage(){
        @Override
        public void deathExplosion(Unit unit, float flammability, float explosiveness, float power, float radius){
        }

        @Override
        public void crash(Unit unit){
        }
    };

    /**
     * {@code Damage.dynamicExplosion(x, y, flammability, explosiveness, power, radius, damage, fire, team, effect)} for
     * a unit not spawned by a core; {@code damage} is {@code rules.damageExplosions && rules.unitCrashDamage(team) > 0}.
     */
    void deathExplosion(Unit unit, float flammability, float explosiveness, float power, float radius);

    /** The crash of a flying wreck type not spawned by a core, when {@code rules.unitCrashDamage(team) > 0}. */
    void crash(Unit unit);

    /**
     * A leg landing ({@code LegsComp.update}, types with {@code legSplashDamage}): {@code Damage.damage} around
     * ({@code x}, {@code y}), and the deconstruction of a {@code unitMoveBreakable} block there.
     */
    default void legSplash(Unit unit, float x, float y){
    }

    static UnitDamage of(World world){
        UnitDamage damage = world.find(UnitDamage.class);
        return damage == null ? NONE : damage;
    }
}
