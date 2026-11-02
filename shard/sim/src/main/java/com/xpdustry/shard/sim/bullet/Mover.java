package com.xpdustry.shard.sim.bullet;

import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code Mover}: what a shoot pattern does to each of its bullets every update ({@code ShootHelix}'s
 * weave), run first thing after the bullet moves. Movers are world state held in a bullet's row, so each is an
 * immutable record: checkpoints store its components and forks share it.
 */
public interface Mover{
    /** {@code move(bullet)}. */
    void move(Bullet bullet);

    /** Feeds the mover's components to the world hash. */
    void hash(Hasher hasher);
}
