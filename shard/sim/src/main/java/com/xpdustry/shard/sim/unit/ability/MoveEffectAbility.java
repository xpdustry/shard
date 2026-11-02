package com.xpdustry.shard.sim.unit.ability;

/**
 * Vanilla's {@code MoveEffectAbility} (MoveEffectAbility.java): trails effects behind a moving unit. Its update
 * returns at once on a headless server ({@code if(Vars.headless) return}), before its counter or its chance, so it
 * keeps and draws nothing. Ported so the kind counts as supported.
 */
@AbilityKind("move-effect-ability")
public final class MoveEffectAbility extends Ability{
}
