package com.xpdustry.shard.sim.building.consume;

import com.xpdustry.shard.sim.building.*;

/**
 * One resource a block consumes (vanilla {@code Consume}): read from the block's {@code consumes} list by
 * {@link Consumers}, shared by every building of the block, and driven by {@link Building#updateConsumption()}.
 *
 * <ul>
 *   <li>{@link #efficiency} is in [0, 1] and says how well the need is met; the building's efficiency is the minimum
 *   over its non-optional consumers.</li>
 *   <li>{@link #update} runs every tick the building works (continuous use: liquids); {@link #trigger} runs when the
 *   kind calls {@link Building#consume()} (discrete use: items, payloads).</li>
 *   <li>{@link #multiplier} scales the amounts: 1, unless the block's definition names a kind hook, which
 *   {@link Building#consumeMultiplier(Consume)} answers.</li>
 * </ul>
 */
public abstract class Consume{
    /** Optional consumers don't limit the building's efficiency; they feed {@code optionalEfficiency}. */
    public final boolean optional;
    /** A booster: optional, and the kind reads its efficiency as a bonus. */
    public final boolean booster;
    /** Whether {@link Building#updateConsumption()} calls {@link #update}; when false the kind updates it itself. */
    public final boolean update;
    private final boolean hooked;

    protected Consume(Flags flags){
        this.optional = flags.optional();
        this.booster = flags.booster();
        this.update = flags.update();
        this.hooked = flags.hookedMultiplier();
    }

    /** The common fields of every consumer: vanilla's {@code optional}, {@code booster}, {@code update} and {@code multiplier}. */
    public record Flags(boolean optional, boolean booster, boolean update, boolean hookedMultiplier){
        /** A required, updated consumer with multiplier 1. */
        public static final Flags DEFAULT = new Flags(false, false, true, false);
    }

    /** Vanilla's {@code multiplier.get(build)}. */
    public final float multiplier(Building building){
        return hooked ? building.consumeMultiplier(this) : 1f;
    }

    /** Whether the consumer list skips this one ({@code ignore()}): no updates, no efficiency. */
    public boolean ignore(){
        return false;
    }

    /** Consumes a discrete amount now ({@code trigger}). */
    public void trigger(Building building){
    }

    /** Continuous use, once per working tick ({@code update}). */
    public void update(Building building){
    }

    /** How well the need is met, in [0, 1]; 0 when it isn't ({@code efficiency}). */
    public float efficiency(Building building){
        return 1f;
    }

    /** A bonus factor that may exceed 1 and never limits base efficiency ({@code efficiencyMultiplier}). */
    public float efficiencyMultiplier(Building building){
        return 1f;
    }
}
