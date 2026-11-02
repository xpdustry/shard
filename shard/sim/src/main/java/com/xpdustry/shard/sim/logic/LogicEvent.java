package com.xpdustry.shard.sim.logic;

import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.world.*;

/**
 * What processors make vanilla send as Calls, as domain events, published where vanilla sends each Call, after its
 * local effects. Positions are tiles, {@code y * width + x}; content and teams are ids. The other world-processor
 * statements change state that clients compute themselves, since they run every processor too (note 28 §1.6).
 */
public sealed interface LogicEvent extends WorldEvent{
    long tick();

    /**
     * {@code Call.syncVariable(build, variable, value)} (LExecutor.java:2077): exported variable {@code variable} of the
     * processor at {@code pos}, as {@link ConfigValue#of} keeps it: a {@code Double}, a string, a {@link ContentRef}, a
     * {@link ConfigValue.Team}, a sensor name, a building's tile or a unit's id. Unreliable.
     */
    record SyncVariable(long tick, int pos, int variable, Object value) implements LogicEvent{
        public SyncVariable{
            value = ConfigValue.of(value);
        }
    }

    /** {@code Call.setFlag(flag, add)}: an objective flag set or cleared. */
    record SetFlag(long tick, String flag, boolean add) implements LogicEvent{
    }

    /** {@code Call.setTile(tile, block, team, rotation)}: {@code setblock block}. */
    record SetTile(long tick, int pos, int block, int team, int rotation) implements LogicEvent{
    }

    /** {@code Call.setFloor(tile, floor, overlay)}: {@code setblock floor}, which clears the overlay. */
    record SetFloor(long tick, int pos, int floor, int overlay) implements LogicEvent{
    }

    /** {@code Call.setOverlay(tile, overlay)}: {@code setblock ore}. */
    record SetOverlay(long tick, int pos, int overlay) implements LogicEvent{
    }

    /** {@code Call.setMapArea(x, y, w, h)}: {@code setrule mapArea} with a new area. */
    record SetMapArea(long tick, int x, int y, int width, int height) implements LogicEvent{
    }
}
