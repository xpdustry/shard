package com.xpdustry.shard.sim.spatial;

/**
 * The tile predicates units collide with (vanilla {@code EntityCollisions.solid}, {@code legsSolid},
 * {@code waterSolid}). Which one a unit uses is its movement shape's {@code solidity()}; a unit that moves freely has
 * none. Outside the map every predicate is solid.
 */
public enum Solidity{
    /** {@code tile.solid()}: a solid block or floor, or a building whose {@code checkSolid()} says so (doors). */
    SOLID,
    /** {@code tile.legSolid()}: a static wall deep in darkness, or a solid floor without a block. */
    LEGS,
    /** {@code tile.solid() || !tile.floor().isLiquid}. */
    WATER
}
