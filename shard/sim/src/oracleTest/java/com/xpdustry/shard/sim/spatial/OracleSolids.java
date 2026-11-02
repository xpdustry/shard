package com.xpdustry.shard.sim.spatial;

import com.xpdustry.shard.sim.world.*;

/**
 * Tile solidity of a vanilla stone world with walls and deep water, for Shard worlds that mirror one (W1.2's real part
 * replaces it): {@code solid} where a wall stands, {@code legSolid} nowhere (no dark interior), {@code waterSolid}
 * wherever the floor is not water or a wall stands, and everything outside the map.
 */
public final class OracleSolids implements WorldPart, TileSolidity{
    private int width, height;
    private boolean[] walls, water;

    @Override
    public void init(World world){
        width = world.tiles().width();
        height = world.tiles().height();
        walls = new boolean[width * height];
        water = new boolean[width * height];
    }

    public void wall(int x, int y){
        walls[y * width + x] = true;
    }

    public void water(int x, int y){
        water[y * width + x] = true;
    }

    @Override
    public boolean solid(Solidity kind, int x, int y){
        if(x < 0 || y < 0 || x >= width || y >= height) return true;
        return switch(kind){
            case SOLID -> walls[y * width + x];
            case LEGS -> false;
            case WATER -> walls[y * width + x] || !water[y * width + x];
        };
    }

    @Override
    public WorldPart copy(CopyContext context){
        var out = new OracleSolids();
        out.width = width;
        out.height = height;
        out.walls = walls.clone();
        out.water = water.clone();
        return out;
    }

    @Override
    public void hash(Hasher hasher){
        for(int i = 0; i < walls.length; i++) hasher.bool(walls[i]).bool(water[i]);
    }
}
