package com.xpdustry.shard.sim.path;

import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;

import static com.xpdustry.shard.sim.path.PathBlocks.*;

/**
 * The world as the path layer last saw it, and what it derives from that: per tile, a {@code view} word (the inputs
 * read from the tile grid and its building at the last flush) and the packed {@link PathTile}.
 *
 * <p>The view is a mirror, not a cache: it holds what the world looked like at the last flush. That makes the fields
 * reproducible at a tick boundary, when the world may already hold changes the next flush will apply. It also carries
 * vanilla's health quirk: a building's health is sampled when its tile changes, never when it is damaged
 * ({@code Pathfinder.java:262}). {@code legSolid} is sampled the same way, from the tile grid, so it reads the data
 * byte vanilla's does: the darkness computed when the map loaded, which a later wall edit never recomputes.
 */
final class PathGrid{
    // view word layout
    private static final int BLOCK_SHIFT = 16, TEAM_SHIFT = 32, HEALTH_SHIFT = 40;
    /** The sampled health term: it keeps the value read at the last change, whatever damage happens since. */
    static final long HEALTH_MASK = 0xffL << HEALTH_SHIFT;
    private static final long HAS_BUILD = 1L << 48, CENTER = 1L << 49, DYNAMIC_SOLID = 1L << 50, ON_SPAWN = 1L << 51,
        LEG_SOLID = 1L << 52;
    /** {@code Geometry.d4}, then {@code Geometry.d8edge}. */
    static final int[] DX = {1, 0, -1, 0, 1, -1, -1, 1}, DY = {0, 1, 0, -1, 1, 1, -1, -1};

    final int width, height, size;
    final PathBlocks blocks;
    PathRules rules;
    final long[] view;
    final int[] tiles;

    PathGrid(int width, int height, PathBlocks blocks, PathRules rules){
        this.width = width;
        this.height = height;
        this.size = width * height;
        this.blocks = blocks;
        this.rules = rules;
        view = new long[size];
        tiles = new int[size];
    }

    PathGrid(PathGrid source){
        width = source.width;
        height = source.height;
        size = source.size;
        blocks = source.blocks;
        rules = source.rules;
        view = source.view.clone();
        tiles = source.tiles.clone();
    }

    // view words

    static int floor(long view){
        return (int)(view & 0xffff);
    }

    static int block(long view){
        return (int)((view >>> BLOCK_SHIFT) & 0xffff);
    }

    static int team(long view){
        return (int)((view >>> TEAM_SHIFT) & 0xff);
    }

    static int health(long view){
        return (int)((view >>> HEALTH_SHIFT) & 0xff);
    }

    static boolean hasBuild(long view){
        return (view & HAS_BUILD) != 0;
    }

    static boolean center(long view){
        return (view & CENTER) != 0;
    }

    static boolean onSpawn(long view){
        return (view & ON_SPAWN) != 0;
    }

    /** What the path layer reads of tile {@code pos} right now. */
    long read(World world, int pos){
        TileGrid grid = world.tiles();
        int floor = grid.floor(pos) & 0xffff, block = grid.block(pos) & 0xffff, overlay = grid.overlay(pos) & 0xffff;
        Building build = world.buildings().at(pos);
        // build.checkSolid(), as Buildings keeps it in the grid: a construct site is solid while either of its blocks is
        // (ConstructBlock.java:193)
        boolean dynamicSolid = grid.buildSolid(pos);
        boolean solid = blocks.is(block, SOLID) || blocks.is(floor, SOLID) || dynamicSolid;
        int team = build == null ? 0 : build.team & 0xff;
        int health = build == null || !solid || blocks.is(block, CORE) ? 0 : Math.max(0, Math.min((int)(build.health / 40f), 80));
        long view = floor | (long)block << BLOCK_SHIFT | (long)team << TEAM_SHIFT | (long)health << HEALTH_SHIFT;
        if(build != null) view |= HAS_BUILD;
        if(build != null && build.tile() == pos) view |= CENTER;
        if(dynamicSolid) view |= DYNAMIC_SOLID;
        if(blocks.is(overlay, SPAWN)) view |= ON_SPAWN;
        if(grid.legSolid(pos)) view |= LEG_SOLID;
        return view;
    }

    // the Tile predicates packTile reads

    /** {@code Tile.solid()}: {@code block.solid || floor.solid || build.checkSolid()}. */
    boolean solid(int pos){
        long v = view[pos];
        return blocks.is(block(v), SOLID) || blocks.is(floor(v), SOLID) || (v & DYNAMIC_SOLID) != 0;
    }

    /** {@code Tile.isDeep()}: {@code block == air && floor.isDeep()}. */
    boolean deep(int pos){
        long v = view[pos];
        return blocks.is(block(v), AIR) && blocks.is(floor(v), DEEP);
    }

    /** {@code Tile.legSolid()}: {@code staticDarkness() >= 2 || (floor.solid && block == air)}, as sampled. */
    boolean legSolid(int pos){
        return (view[pos] & LEG_SOLID) != 0;
    }

    /** A port of {@code Pathfinder.packTile} ({@code Pathfinder.java:221}), from the view. */
    int pack(int pos){
        int x = pos % width, y = pos / width;
        long v = view[pos];
        int floor = floor(v), block = block(v);
        boolean solid = solid(pos), allDeep = deep(pos), nearDeep = allDeep;
        boolean nearLiquid = false, nearSolid = false, nearLegSolid = false, nearGround = false;
        for(int d = 0; d < 4; d++){
            int ox = x + DX[d], oy = y + DY[d];
            if(ox < 0 || oy < 0 || ox >= width || oy >= height) continue;
            int other = ox + oy * width;
            long ov = view[other];
            boolean otherDeep = deep(other), otherLiquid = blocks.is(floor(ov), LIQUID);
            if(otherLiquid && otherDeep) nearLiquid = true;
            // vanilla also ORs nearSolid into the neighbours of a solid tile; on a fresh pack that is the same predicate
            if(solid(other) && !blocks.is(block(ov), TEAM_PASSABLE)) nearSolid = true;
            if(!otherLiquid) nearGround = true;
            if(!otherDeep) allDeep = false;
            else nearDeep = true;
            if(legSolid(other)) nearLegSolid = true;
        }
        if(allDeep){
            for(int d = 4; d < 8; d++){
                int ox = x + DX[d], oy = y + DY[d];
                if(ox >= 0 && oy >= 0 && ox < width && oy < height && !deep(ox + oy * width)){
                    allDeep = false;
                    break;
                }
            }
        }
        int team = team(v);
        // team 255 marks derelict structures when core capture is on
        if(team == 0 && hasBuild(v) && rules.coreCapture()) team = 255;
        int tile = health(v) | team << 8;
        if(solid) tile |= PathTile.SOLID;
        if(blocks.is(floor, LIQUID) && !blocks.is(block, SOLID)) tile |= PathTile.LIQUID;
        if(legSolid(pos)) tile |= PathTile.LEG_SOLID;
        if(nearLiquid) tile |= PathTile.NEAR_LIQUID;
        if(nearGround) tile |= PathTile.NEAR_GROUND;
        if(nearSolid) tile |= PathTile.NEAR_SOLID;
        if(nearLegSolid) tile |= PathTile.NEAR_LEG_SOLID;
        if(deep(pos)) tile |= PathTile.DEEP;
        if(blocks.is(floor, DAMAGES)) tile |= PathTile.DAMAGES;
        if(allDeep) tile |= PathTile.ALL_DEEP;
        if(nearDeep) tile |= PathTile.NEAR_DEEP;
        if(blocks.is(block, TEAM_PASSABLE)) tile |= PathTile.TEAM_PASSABLE;
        // Tile.dangerous(): !block.solid && (floor.isDeep() || floor.damages())
        if(!blocks.is(block, SOLID) && (blocks.is(floor, DEEP) || blocks.is(floor, DAMAGES))) tile |= PathTile.DANGEROUS;
        return tile;
    }

    /** Reads every tile, then packs every tile. */
    void refreshAll(World world){
        for(int pos = 0; pos < size; pos++) view[pos] = read(world, pos);
        packAll();
    }

    /** Packs every tile from the view. */
    void packAll(){
        for(int pos = 0; pos < size; pos++) tiles[pos] = pack(pos);
    }

    void hash(Hasher hasher){
        hasher.i(width).i(height);
        for(long v : view) hasher.l(v);
    }
}
