package com.xpdustry.shard.sim.combat;

import com.xpdustry.shard.common.registry.*;

import java.lang.annotation.*;

/**
 * Registers a {@link Weapon} subclass as the behaviour of a weapon kind:
 * {@code @WeaponKind("point-defense-weapon") public class PointDefenseWeapon extends Weapon}. Weapons whose kind is that
 * key, or extends it without a class of its own, run it ({@link Armory}). The class must be public and concrete with
 * a public no-argument constructor.
 */
@Register
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface WeaponKind{
    /** The kebab-case kind key, as the content names it. */
    String value();
}
