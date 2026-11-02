/**
 * Combat as data (note 31).
 * <ul>
 *   <li>Damage: {@link com.xpdustry.shard.sim.combat.Damage} (area damage, {@code tileDamage}, explosions),
 *   {@link com.xpdustry.shard.sim.combat.Lightning}, and the {@link com.xpdustry.shard.sim.combat.Combat} part, which
 *   keeps the JVM statics vanilla's damage relies on per world and is the unit and building death seams.</li>
 *   <li>Weapons: {@link com.xpdustry.shard.sim.combat.Weapon} and its kinds (point defense, repair beam, build), each
 *   registered with {@link com.xpdustry.shard.sim.combat.WeaponKind}; {@link com.xpdustry.shard.sim.combat.ShootPattern};
 *   a unit type's {@link com.xpdustry.shard.sim.combat.Arms} in the world's {@link com.xpdustry.shard.sim.combat.Armory};
 *   and {@link com.xpdustry.shard.sim.combat.Weapons}, the {@code WeaponsComp} unit component with the calls
 *   controllers make ({@code aim}, {@code controlWeapons}). The mounts' state is {@code sim.unit.WeaponMount}.</li>
 *   <li>Seams for what other packages own: {@link com.xpdustry.shard.sim.combat.StatusApply} (status effects, WP42)
 *   and {@link com.xpdustry.shard.sim.combat.Hazards} (fires and puddles).</li>
 * </ul>
 *
 * <p>Owners: WP41 (damage), WP43 (weapons), WP42 (status, weather), WP61 (special bullets), WP62 (abilities).
 */
package com.xpdustry.shard.sim.combat;
