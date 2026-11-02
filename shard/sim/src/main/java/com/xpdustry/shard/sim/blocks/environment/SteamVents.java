package com.xpdustry.shard.sim.blocks.environment;

import com.xpdustry.shard.sim.tile.*;

/**
 * {@code SteamVent}'s rules. A vent is a 3×3 patch of one vent floor; vent condensers and turbines need a whole one
 * under them, and the indexer flags only its centre ({@code shouldIndex}). The vent's top-right tile is the one whose
 * 3×3 below-left neighbourhood is checked, for vanilla's render order.
 */
public final class SteamVents{
    /** {@code SteamVent.offsets} after its static initializer subtracts (1, 1): the top-right tile's patch. */
    private static final int[] DX = {-1, 0, 0, -1, -2, -2, -2, -1, 0}, DY = {-1, -1, 0, 0, 0, -1, -2, -2, -2};

    /** {@code checkAdjacent(tile)}: the 3×3 patch below-left of (x, y), (x, y) included, is all {@code vent} floor. */
    public static boolean checkAdjacent(TileGrid tiles, int x, int y, int vent){
        for(int i = 0; i < DX.length; i++){
            int ox = x + DX[i], oy = y + DY[i];
            if(!tiles.inBounds(ox, oy) || tiles.floor(tiles.pos(ox, oy)) != vent) return false;
        }
        return true;
    }

    /** {@code isCenterVent(tile)}: (x, y) is the centre of a whole vent of its own floor. */
    public static boolean isCenterVent(TileGrid tiles, int x, int y){
        if(!tiles.inBounds(x + 1, y + 1)) return false;
        int floor = tiles.floor(tiles.pos(x, y));
        return tiles.floor(tiles.pos(x + 1, y + 1)) == floor && checkAdjacent(tiles, x + 1, y + 1, floor);
    }

    private SteamVents(){}
}
