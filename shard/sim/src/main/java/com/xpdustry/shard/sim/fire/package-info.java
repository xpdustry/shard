/**
 * Fires (WP61): the pooled {@link com.xpdustry.shard.sim.fire.Fire} entity and
 * {@link com.xpdustry.shard.sim.fire.Fires}, the world's tile map with vanilla's static calls ({@code create},
 * {@code extinguish}, {@code has}, {@code get}) and tile flammability, which also answers combat's
 * {@link com.xpdustry.shard.sim.combat.Hazards} seam. {@link com.xpdustry.shard.sim.fire.HazardPool} replays the random
 * draws of the fire and puddle pools' {@code reset()}.
 *
 * <p>Owner: WP61 (fires and puddles).
 */
package com.xpdustry.shard.sim.fire;
