package com.xpdustry.shard.sim.building;

/**
 * Hears, synchronously and inside the call that makes it, of every change to the building that stands on a tile: one
 * placed, removed, replaced or destroyed there, and a standing building's team changing, once for every tile the
 * building covers. Code that must react in the same tick implements it on its {@link com.xpdustry.shard.sim.world.WorldPart}
 * (processor links, WP67); everything else reads {@code TileGrid#changes} a tick later. Map loads don't notify: a
 * watcher starts over at the end of the load.
 */
public interface TileWatcher{
    void buildingChanged(int pos);

    /**
     * Vanilla's {@code TilePreChangeEvent} on {@code pos}: its building, if any, is still there. Not on a team change.
     * After the block indexer's handler, as vanilla registers it first.
     */
    default void tileChanging(com.xpdustry.shard.sim.world.World world, int pos){
    }

    /**
     * Vanilla's {@code TileChangeEvent} on {@code pos}: the tile holds its new block. Not on a team change. After the
     * block indexer's handler, before observers hear of it.
     */
    default void tileChanged(com.xpdustry.shard.sim.world.World world, int pos){
    }

    /** A map load ended ({@code WorldLoadEvent}): the tiles are as loaded, and the watcher starts over from them. */
    default void loaded(){
    }

    /** {@link #loaded()}, for a watcher that keeps no world of its own. */
    default void loaded(com.xpdustry.shard.sim.world.World world){
        loaded();
    }
}
