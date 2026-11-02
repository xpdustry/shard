/**
 * Consumers (vanilla {@code world/consumers}): what a block needs to work, read as data from its definition's
 * {@code consumes} list into {@link com.xpdustry.shard.sim.building.consume.Consumers}, and driven by
 * {@link com.xpdustry.shard.sim.building.Building#updateConsumption()}. Variants that differ from a vanilla class only by
 * their filter or multiplier are one class here, with the vanilla class names in its comment. Power reads
 * {@code PowerModule.status}, which the power graphs ({@code sim.blocks.power}) fill.
 *
 * <p>Owner: WP20.
 */
package com.xpdustry.shard.sim.building.consume;
