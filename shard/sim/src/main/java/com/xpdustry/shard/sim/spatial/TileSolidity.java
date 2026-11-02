package com.xpdustry.shard.sim.spatial;

import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Tile solidity for unit movement, by {@link Solidity} predicate (note 29 §1.4): vanilla's {@code EntityCollisions}
 * {@code solid}, {@code legsSolid} and {@code waterSolid}, each true outside the map.
 *
 * <p>The answer is the {@link TileGrid}'s predicate bitsets ({@link #grid(TileGrid)}). The grid's {@code solid} bit
 * already holds {@code Building#checkSolid()} (doors): {@code Buildings} sets it when a building is placed or says its
 * answer changed, so collisions never call into buildings. A world may replace the grid with a {@link WorldPart} that
 * implements this interface (a test fixture); {@link #of(World)} finds the first one.
 */
public interface TileSolidity{
    /** Whether tile ({@code x}, {@code y}) stops a unit that collides by {@code kind}; always true outside the map. */
    boolean solid(Solidity kind, int x, int y);

    /** The world's solidity part if it has one, else its grid's bitsets. */
    static TileSolidity of(World world){
        TileSolidity solidity = world.find(TileSolidity.class);
        return solidity == null ? grid(world.tiles()) : solidity;
    }

    /**
     * The grid's bitsets: {@code solid} (blocks, solid floors, {@code checkSolid}), {@code legSolid} and {@code waterSolid}.
     * The grid is its own view ({@link TileGrid#solid(Solidity, int, int)}), so a lookup per unit move allocates
     * nothing, and a forked or restored world's grid answers for that world only.
     */
    static TileSolidity grid(TileGrid tiles){
        return tiles;
    }

    /** Only tiles outside the map are solid, as on an empty floor. */
    static TileSolidity bounds(TileGrid tiles){
        return (kind, x, y) -> !tiles.inBounds(x, y);
    }
}
