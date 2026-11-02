package com.xpdustry.shard.sim.logic;

import com.xpdustry.shard.sim.world.*;

/**
 * The Calls processors' unit and world statements make vanilla send (WP69), as domain events published where vanilla
 * sends each Call, after its local effects, as {@link LogicEvent} does for WP67's. Content and teams are ids. Their
 * item transfers ({@code ucontrol itemDrop}, {@code itemTake}) are {@code sim.unit.ItemTransfers}' events, as everyone's.
 */
public sealed interface LogicWorldEvent extends WorldEvent{
    long tick();

    /**
     * {@code Call.logicExplosion(team, x, y, radius, damage, air, ground, pierce, effect)} (LExecutor.java:2028), in world
     * units; {@code team} -1 for none. Unreliable.
     */
    record Explosion(long tick, int team, float x, float y, float radius, float damage, boolean air, boolean ground, boolean pierce,
                     boolean effect) implements LogicWorldEvent{
    }
}
