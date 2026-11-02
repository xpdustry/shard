package com.xpdustry.shard.sim.path;

/**
 * One packed path tile, laid out bit for bit like vanilla's generated {@code PathTile} ({@code PathTileStruct},
 * {@code Pathfinder.java:717}): scaled health in bits 0–7, team in 8–15, then one bit per flag in declaration order.
 * Bit 28 is Shard's own: {@code Tile.dangerous()}, which vanilla reads from the tile instead
 * ({@code Pathfinder.java:435}).
 */
public final class PathTile{
    public static final int
        SOLID = 1 << 16,
        LIQUID = 1 << 17,
        LEG_SOLID = 1 << 18,
        NEAR_LIQUID = 1 << 19,
        NEAR_GROUND = 1 << 20,
        NEAR_SOLID = 1 << 21,
        NEAR_LEG_SOLID = 1 << 22,
        DEEP = 1 << 23,
        DAMAGES = 1 << 24,
        ALL_DEEP = 1 << 25,
        NEAR_DEEP = 1 << 26,
        TEAM_PASSABLE = 1 << 27,
        DANGEROUS = 1 << 28;

    /** The bits vanilla's {@code PathTile} has; everything above is Shard's. */
    public static final int VANILLA_BITS = (1 << 28) - 1;

    public static int health(int tile){
        return tile & 0xff;
    }

    public static int team(int tile){
        return (tile >>> 8) & 0xff;
    }

    public static boolean is(int tile, int flag){
        return (tile & flag) != 0;
    }

    private PathTile(){}
}
