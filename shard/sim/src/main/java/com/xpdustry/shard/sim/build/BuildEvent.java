package com.xpdustry.shard.sim.build;

import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Construction's Calls as domain events (WAVES.md "Contracts for 2c"), one record per vanilla remote method, which the
 * sync layer sends as the packet of the same name to everyone. Each is published where vanilla's {@code Call} sends
 * its packet: after the local handler ran, so a Call the handler makes goes first (a {@code tileConfig} from
 * {@code placed()} follows its {@code constructFinish}; a {@code constructFinish} precedes the {@code beginPlace} of an
 * instant block, which finishes inside it without a Call of its own). Vanilla sends the Call even when its handler
 * refused the request, and so is the event published.
 *
 * <p>Tiles are given as x and y (the building's origin); units by entity id, -1 for none ({@code TypeIO.writeUnit}'s
 * null). Configs are kept as {@link ConfigValue#of} makes them.
 */
public sealed interface BuildEvent extends WorldEvent{
    long tick();

    int x();

    int y();

    /**
     * {@code Call.beginPlace(unit, result, team, x, y, rotation, placeConfig)} (Build.java:71): a site for
     * {@code block} began here, a standing block rotated in place, or a derelict one was repaired. {@code config} is
     * only sent for instant blocks, null otherwise.
     */
    record BeginPlace(long tick, int unit, int block, int team, int x, int y, int rotation, Object config) implements BuildEvent{
        public BeginPlace{
            config = ConfigValue.of(config);
        }
    }

    /** {@code Call.beginBreak(unit, team, x, y)} (Build.java:25): the block covering (x, y) began to be broken. */
    record BeginBreak(long tick, int unit, int team, int x, int y) implements BuildEvent{
    }

    /**
     * {@code Call.constructFinish(tile, block, builder, rotation, team, config)} (ConstructBlock.java:72): the site at
     * (x, y) became {@code block}, configured with {@code config}. {@code placed()} follows, with its own Calls.
     */
    record ConstructFinish(long tick, int x, int y, int block, int builder, int rotation, int team, Object config) implements BuildEvent{
        public ConstructFinish{
            config = ConfigValue.of(config);
        }
    }

    /** {@code Call.deconstructFinish(tile, block, builder)} (ConstructBlock.java:59): {@code block} at (x, y) is gone. */
    record DeconstructFinish(long tick, int x, int y, int block, int builder) implements BuildEvent{
    }
}
