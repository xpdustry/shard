package com.xpdustry.shard.sim.blocks.storage;

import com.xpdustry.shard.sim.world.*;

/**
 * What a core tells clients with a Call, as domain events: published where vanilla sends the Call, after its local
 * effects. Positions are origin tiles, {@code y * width + x}.
 */
public sealed interface CoreEvent extends WorldEvent{
    long tick();

    int pos();

    /**
     * {@code Call.playerSpawn(tile, player)}: the player ({@code player}, an entity id) took {@code unit}, a new unit of
     * the core's type at {@code pos}, which is already added. The call names the tile and the player; the unit reaches
     * clients with the next entity snapshot.
     */
    record PlayerSpawn(long tick, int pos, int player, int unit) implements CoreEvent{
    }

    /** {@code Call.setTile(tile, block, team, rotation)}, from a captured core's posted {@code setNet}. */
    record SetTile(long tick, int pos, int block, int team, int rotation) implements CoreEvent{
    }
}
