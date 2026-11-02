package com.xpdustry.shard.compat.v160.filters;

import com.xpdustry.shard.common.math.*;

/**
 * Vanilla's {@code GenerateFilter.GenerateInput}: the tile a per-tile filter is looking at, as block ids, and what it
 * turns it into. One per filter pass. Shard's filters are immutable, so the seed vanilla keeps in the filter
 * ({@code GenerateFilter.seed}) is here.
 */
public final class GenerateInput{
    /** The tile and the map size. */
    public int x, y, width, height;
    /** The output: block ids. */
    public int floor, block, overlay;
    /** The output's packed data ({@code Tile.getPackedData()}): {@code data}, {@code floorData}, {@code overlayData}, {@code extraData}. */
    public byte data, floorData, overlayData;
    public int extraData;
    /** The running filter's seed, {@code Mathf.random(999999999)} at the start of its pass. */
    public final int seed;

    final FilterGrid grid;

    GenerateInput(FilterGrid grid, int seed){
        this.grid = grid;
        this.seed = seed;
        this.width = grid.width();
        this.height = grid.height();
    }

    /** {@code set(Tile)}: the tile at {@code pos} as it is now. */
    void set(int pos){
        x = grid.x(pos);
        y = grid.y(pos);
        floor = grid.floor(pos);
        block = grid.block(pos);
        overlay = grid.overlay(pos);
        data = grid.tiles.data(pos);
        floorData = grid.tiles.floorData(pos);
        overlayData = grid.tiles.overlayData(pos);
        extraData = grid.tiles.extraData(pos);
    }

    /** {@code tile(x, y)}: the position of the live tile at the truncated coordinates, clamped into the map. */
    public int tile(float x, float y){
        return grid.pos(Mathf.clamp((int)x, 0, width - 1), Mathf.clamp((int)y, 0, height - 1));
    }

    public FilterGrid grid(){
        return grid;
    }

    // GenerateFilter's noise helpers; "+ 10 is added as noise has similar values at 0,0"

    /** {@code noise(seedOffset, in, scl, mag)}. */
    public float noise(int seedOffset, float scl, float mag){
        return Simplex.noise2d(seedOffset + seed, 1f, 0f, 1f / scl, x + 10, y + 10) * mag;
    }

    /** {@code noise(in, scl, mag)}. */
    public float noise(float scl, float mag){
        return Simplex.noise2d(seed, 1f, 0f, 1f / scl, x + 10, y + 10) * mag;
    }

    /** {@code noise(x, y, scl, mag, octaves, persistence)}: float coordinates, so the {@code + 10} is a float sum. */
    public float noise(float x, float y, float scl, float mag, float octaves, float persistence){
        return Simplex.noise2d(seed, octaves, persistence, 1f / scl, x + 10, y + 10) * mag;
    }

    /** {@code rnoise(x, y, octaves, scl, falloff, mag)}: ridged noise on the truncated coordinates, seed + 1. */
    public float rnoise(float x, float y, int octaves, float scl, float falloff, float mag){
        return Ridged.noise2d(seed + 1, (int)x, (int)y, octaves, falloff, 1f / scl) * mag;
    }

    /** {@code chance(x, y)}: a float in [0, 1) hashed from the position and seed. */
    public float chance(int x, int y){
        return Mathf.randomSeed(((long)x << 32) | ((y + seed) & 0xffffffffL));
    }
}
