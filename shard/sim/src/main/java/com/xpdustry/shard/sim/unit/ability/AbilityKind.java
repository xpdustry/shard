package com.xpdustry.shard.sim.unit.ability;

import com.xpdustry.shard.common.registry.*;

import java.lang.annotation.*;

/**
 * Registers an {@link Ability} subclass as the behaviour of an ability kind:
 * {@code @AbilityKind("force-field-ability") public final class ForceFieldAbility extends Ability}. Abilities whose kind
 * is that key, or extends it without a class of its own, run it ({@link Abilities}). The class must be public and
 * concrete with a public no-argument constructor.
 */
@Register
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface AbilityKind{
    /** The kebab-case kind key, as the content names it. */
    String value();
}
