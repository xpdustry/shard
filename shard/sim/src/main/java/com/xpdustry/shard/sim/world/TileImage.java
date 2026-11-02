package com.xpdustry.shard.sim.world;

/**
 * A tile's columns at one moment: the before-image a {@link Journal} entry keeps. WP32 (rollback, block logs) adds the
 * building's own state, which rollback needs to restore more than the block.
 *
 * @param pos {@code y * width + x}
 * @param build the building registry index, or -1
 * @param data vanilla's {@code Tile.data}: block data, a static wall's darkness
 * @param floorData vanilla's {@code Tile.floorData}
 * @param overlayData vanilla's {@code Tile.overlayData}
 * @param extraData vanilla's {@code Tile.extraData}: a colored floor's or wall's color, a colored wall's dark flag
 */
public record TileImage(int pos, short floor, short overlay, short block, byte team, int build, byte data, byte floorData,
                        byte overlayData, int extraData){
}
