package com.xpdustry.shard.sim.team;

import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code CoreChangeEvent(core)} (EventType.java:503), fired by a core building as a domain event for
 * plugins: it maps to no Call. Published where vanilla fires it, in the same order with the rest of the tick:
 * <ul>
 *   <li>{@link Change#CREATED}: the core was put on its tiles ({@code created()}, CoreBlock.java:593), from a map
 *   load, a construction, a capture or a placed payload;</li>
 *   <li>{@link Change#TEAM}: the core moved to {@code team} ({@code changeTeam}, CoreBlock.java:606);</li>
 *   <li>{@link Change#DESTROYED}: the core was destroyed, {@code team} being the team that lost it ({@code onDestroyed},
 *   CoreBlock.java:708). In PvP, the dead-team check that vanilla posts on this event runs the next tick
 *   ({@link DeadTeams}).</li>
 * </ul>
 *
 * @param pos the core's tile, {@code Point2.pack(x, y)} as vanilla's {@code build.pos()}
 * @param block the core's block id
 * @param team the core's team once the change was made
 */
public record CoreChangeEvent(long tick, int pos, int block, int team, Change change) implements WorldEvent{
    /** Which of vanilla's three sites fired it. */
    public enum Change{
        CREATED, TEAM, DESTROYED
    }
}
