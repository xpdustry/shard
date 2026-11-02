/**
 * Unit abilities (WP62): vanilla's {@code entities/abilities}, one {@link com.xpdustry.shard.sim.unit.ability.Ability}
 * subclass per kind, registered with {@link com.xpdustry.shard.sim.unit.ability.AbilityKind}, and
 * {@link com.xpdustry.shard.sim.unit.ability.Abilities}, a unit type's list, which the unit's {@code UnitTable} keeps
 * and the unit core runs ({@code created}, {@code update}, {@code death}). An ability is the type's and immutable; each
 * unit holds its state in a {@link com.xpdustry.shard.sim.unit.UnitAbility} per ability, which is what checkpoints,
 * forks and hashes carry, and whose {@code data} the wire syncs and saves. Kinds that keep only a timer share
 * {@link com.xpdustry.shard.sim.unit.ability.Timed}.
 *
 * <p>Owner: WP62.
 */
package com.xpdustry.shard.sim.unit.ability;
