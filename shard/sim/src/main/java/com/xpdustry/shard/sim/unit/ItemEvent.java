package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.sim.world.*;

/**
 * Items moving between a unit and a building, one record per vanilla {@code InputHandler} remote call, published by
 * {@link ItemTransfers} where the {@code Call} sends its packet: after its local handler ran, whatever the handler
 * found. Both Calls are unreliable and go to everyone.
 *
 * <p>Units are named as {@code TypeIO.writeUnit} names them: {@code unit} is the entity id, or -1 for none, and
 * {@code blockUnit} the turret's packed position ({@code Point2.pack}) when the unit is a turret's block unit, else -1.
 * Buildings are tiles, {@code y * width + x}; items are content ids.
 */
public sealed interface ItemEvent extends WorldEvent{
    /**
     * {@code Call.transferItemTo(unit, item, amount, x, y, build)} (InputHandler.java:241): {@code amount} of the unit's
     * {@code item} went into the building at {@code pos}, flying from ({@code x}, {@code y}).
     */
    record TransferItemTo(int unit, int blockUnit, int item, int amount, float x, float y, int pos) implements ItemEvent{
    }

    /** {@code Call.takeItems(build, item, amount, to)} (InputHandler.java:166): the unit asked for {@code amount}. */
    record TakeItems(int pos, int item, int amount, int unit, int blockUnit) implements ItemEvent{
    }
}
