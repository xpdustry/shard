package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.world.*;

/**
 * What drives a unit: a player's input, an AI, a logic processor (vanilla {@code UnitController}). A controller
 * belongs to one unit and is copied with it; a player's control is a {@link PlayerControl} pointing at the
 * {@link Player}, so the player itself is never copied as part of a unit.
 *
 * <p>Owners: WP54 (AI controllers), WP21 (player control and the unit core's calls into this).
 */
public interface UnitController{
    /** Vanilla's {@code updateUnit}, once per tick for the controlled unit, from {@code UnitComp.update}. */
    void updateUnit(Unit unit);

    /**
     * Vanilla's {@code unit(unit)} when the unit changes: {@link Unit#controller(UnitController)} calls it. An AI's
     * {@code init()} runs here ({@code CommandAI} picks its default command).
     */
    default void bound(Unit unit){
    }

    /**
     * Vanilla's {@code hit(bullet)}: a bullet {@code owner} fired hit {@code unit}, directly ({@code UnitComp.collision})
     * or by its splash ({@code Damage.damage}). Only {@code CommandAI} reacts (it counter-attacks).
     */
    default void hit(Unit unit, Entity owner){
    }

    /** Vanilla's {@code removed}: the controlled unit left the world ({@link Unit#remove()}). */
    default void removed(Unit unit){
    }

    /** Vanilla's {@code isValidController}: when false, the unit core resets the unit to its type's controller. */
    default boolean isValidController(){
        return true;
    }

    /** A copy for a fork; map references to world objects (targets, the unit) through {@code context}. */
    UnitController copy(CopyContext context);

    /** Feeds the controller's authoritative state (timers, targets as ids) to the world hash. */
    void hash(Hasher hasher);
}
