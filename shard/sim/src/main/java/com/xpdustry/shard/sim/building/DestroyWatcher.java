package com.xpdustry.shard.sim.building;

/**
 * Hears vanilla's {@code BlockDestroyEvent}: a building is being destroyed ({@code killed()}), already dead, before its
 * {@code onDestroyed} explosion and while its tiles still hold it. Implemented on a
 * {@link com.xpdustry.shard.sim.world.WorldPart}; every such part hears it, in parts order. Construction turns a
 * destroyed block into its team's rebuild plan here (WP30); map loads don't notify.
 */
public interface DestroyWatcher{
    void destroying(Building building);
}
