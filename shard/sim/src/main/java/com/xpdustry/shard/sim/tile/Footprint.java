package com.xpdustry.shard.sim.tile;

import com.xpdustry.shard.common.math.*;

import java.util.*;
import java.util.function.*;

/**
 * Multiblock geometry, as vanilla's {@code Block} and {@code Edges}. A block of size {@code s} placed at origin
 * (x, y) covers {@code x + offset(s) .. x + offset(s) + s - 1} on each axis, with {@code offset(s) = -(s - 1) / 2}:
 * odd sizes are centred on the origin, even sizes reach one tile further up and right. In world units the centre is
 * {@code x * 8 + worldOffset(s)}, so an even block's centre lies on the corner between four tiles.
 *
 * <p>Footprints iterate x outer, y inner ({@code for dx { for dy } }), the order of {@code Tile.getLinkedTiles} and
 * of {@code setBlock}'s two passes. Edges are the tiles that touch a footprint's sides (corners excluded), in
 * vanilla's {@code Edges.getEdges} order: sorted by {@code Mathf.angle}, ties kept in generation order.
 */
public final class Footprint{
    /** World units per tile ({@code Vars.tilesize}). */
    public static final int TILE_SIZE = 8;
    /** The largest block size ({@code Vars.maxBlockSize}). */
    public static final int MAX_SIZE = 16;

    private static final int[][] EDGE_X = new int[MAX_SIZE][], EDGE_Y = new int[MAX_SIZE][];
    private static final int[][] INSIDE_X = new int[MAX_SIZE][], INSIDE_Y = new int[MAX_SIZE][];

    static{
        for(int i = 0; i < MAX_SIZE; i++){
            // Edges.java:22-48, verbatim
            int bot = -(int)(i / 2f) - 1;
            int top = (int)(i / 2f + 0.5f) + 1;
            var edges = new int[(i + 1) * 4][];
            int idx = 0;
            for(int j = 0; j < i + 1; j++){
                edges[idx++] = new int[]{bot + 1 + j, bot};
                edges[idx++] = new int[]{bot + 1 + j, top};
                edges[idx++] = new int[]{bot, bot + j + 1};
                edges[idx++] = new int[]{top, bot + j + 1};
            }
            Arrays.sort(edges, (a, b) -> Float.compare(Mathf.angle(a[0], a[1]), Mathf.angle(b[0], b[1])));
            EDGE_X[i] = new int[edges.length];
            EDGE_Y[i] = new int[edges.length];
            INSIDE_X[i] = new int[edges.length];
            INSIDE_Y[i] = new int[edges.length];
            for(int j = 0; j < edges.length; j++){
                EDGE_X[i][j] = edges[j][0];
                EDGE_Y[i][j] = edges[j][1];
                INSIDE_X[i][j] = Mathf.clamp(edges[j][0], -(int)(i / 2f), (int)(i / 2f + 0.5f));
                INSIDE_Y[i][j] = Mathf.clamp(edges[j][1], -(int)(i / 2f), (int)(i / 2f + 0.5f));
            }
        }
    }

    /** {@code Block.sizeOffset}. */
    public static int offset(int size){
        return -(size - 1) / 2;
    }

    /** {@code Block.offset}: from the origin tile's centre to the block's, in world units. */
    public static float worldOffset(int size){
        return ((size + 1) % 2) * TILE_SIZE / 2f;
    }

    /** How many edge tiles a block of {@code size} has: {@code 4 * size}. */
    public static int edgeCount(int size){
        return EDGE_X[check(size) - 1].length;
    }

    /** The x offset of edge {@code i} from the origin, in {@code Edges.getEdges(size)} order. */
    public static int edgeX(int size, int i){
        return EDGE_X[size - 1][i];
    }

    public static int edgeY(int size, int i){
        return EDGE_Y[size - 1][i];
    }

    /** {@code Edges.getInsideEdges(size)}: each edge clamped into the footprint. */
    public static int insideEdgeX(int size, int i){
        return INSIDE_X[size - 1][i];
    }

    public static int insideEdgeY(int size, int i){
        return INSIDE_Y[size - 1][i];
    }

    /**
     * Visits the position of every in-bounds tile of a {@code size} footprint at origin (x, y), x outer and y inner.
     * Tiles outside the map are skipped, as vanilla's {@code world.tile} returns null for them.
     */
    public static void each(TileGrid tiles, int x, int y, int size, IntConsumer action){
        int offset = offset(size);
        for(int dx = 0; dx < size; dx++){
            for(int dy = 0; dy < size; dy++){
                int wx = x + dx + offset, wy = y + dy + offset;
                if(tiles.inBounds(wx, wy)) action.accept(tiles.pos(wx, wy));
            }
        }
    }

    /** Whether the whole {@code size} footprint at (x, y) lies inside the map. */
    public static boolean inBounds(TileGrid tiles, int x, int y, int size){
        int offset = offset(size);
        return tiles.inBounds(x + offset, y + offset) && tiles.inBounds(x + offset + size - 1, y + offset + size - 1);
    }

    /** Whether (tx, ty) lies inside the {@code size} footprint at origin (x, y). */
    public static boolean contains(int x, int y, int size, int tx, int ty){
        int offset = offset(size);
        return tx >= x + offset && ty >= y + offset && tx < x + offset + size && ty < y + offset + size;
    }

    /**
     * {@code Edges.getFacingEdge(block, x, y, other)}: the tile of a {@code size} block at (x, y) nearest to (ox, oy),
     * as offsets from the origin {@code {dx, dy}}.
     */
    public static int facingEdgeX(int size, int x, int ox){
        return size <= 1 ? 0 : Mathf.clamp(ox - x, -(size - 1) / 2, size / 2);
    }

    public static int facingEdgeY(int size, int y, int oy){
        return facingEdgeX(size, y, oy);
    }

    private static int check(int size){
        if(size < 1 || size > MAX_SIZE) throw new IllegalArgumentException("block size must be between 1 and " + MAX_SIZE + ": " + size);
        return size;
    }

    private Footprint(){}
}
