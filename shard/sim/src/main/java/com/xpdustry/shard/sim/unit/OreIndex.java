package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;

/**
 * The ore half of vanilla's {@code BlockIndexer} (BlockIndexer.java:34-41, 95-118, 233-291, 526-577): for each item
 * and 20-tile quadrant, the tiles it can be mined from, on the floor (no block on the tile, {@code Tile.drop()}) and
 * in walls ({@code Tile.wallDrop()}), with how many of each the map has. Miners and processors ({@code ulocate ore})
 * look ores up here, which is the only owner of this index.
 *
 * <p>Order is vanilla's history, kept exactly: a map load fills the lists in tile order; then every
 * {@code TileChangeEvent} ({@link TileWatcher#buildingChanged}, which {@link Buildings} fires where vanilla does) runs
 * {@code addIndex} over the tile's footprint: a tile left without a block appends its floor ore if it isn't listed
 * and drops its wall ore, a covered tile appends its wall ore and drops its floor ore. Lists are unordered
 * ({@code IntSeq(false, 16)}), so a drop swaps the last entry into the gap. Floor and overlay changes don't reach it,
 * as they don't reach vanilla's. A lookup reads only each quadrant's first entry ({@link #findClosestOre}).
 *
 * <p>A world that never loaded a map (built in code) indexes its tiles as they are at the first lookup.
 *
 * <p>{@link Persistence#AUTHORITATIVE}, in its own section ({@link SectionPart}): the lists are history.
 *
 * <p>Owner: WP45.
 */
@Register
public final class OreIndex implements SectionPart, TileWatcher{
    /** {@code BlockIndexer.quadrantSize}. */
    public static final int QUADRANT = 20;
    /** The section format. */
    static final int VERSION = 1;
    private static final float TILESIZE = 8f;

    @Derived("the world, from init, the copy or the restore")
    private World world;
    private boolean loaded;
    private int quadWidth, quadHeight;
    /** Per item id, per quadrant ({@code qx * quadHeight + qy}): the floor ore tiles, or null. */
    private IntSeq[][] ores = new IntSeq[0][];
    /** As {@link #ores}, for wall ores. */
    private IntSeq[][] wallOres = new IntSeq[0][];
    /** {@code allOres} and {@code allWallOres}: per item id, the tiles listed. */
    private int[] oreCounts = new int[0], wallOreCounts = new int[0];

    @Override
    public void init(World world){
        this.world = world;
    }

    public static OreIndex of(World world){
        return world.find(OreIndex.class);
    }

    // region index

    /** {@code WorldLoadEvent}: every tile in order, an uncovered one by its floor ore, a covered one by its wall ore. */
    @Override
    public void loaded(){
        TileGrid tiles = world.tiles();
        int items = world.content().items();
        quadWidth = Mathf.ceil(tiles.width() / (float)QUADRANT);
        quadHeight = Mathf.ceil(tiles.height() / (float)QUADRANT);
        ores = new IntSeq[items][];
        wallOres = new IntSeq[items][];
        oreCounts = new int[items];
        wallOreCounts = new int[items];
        for(int pos = 0; pos < tiles.size(); pos++){
            int qx = tiles.x(pos) / QUADRANT, qy = tiles.y(pos) / QUADRANT, drop;
            if(tiles.block(pos) == 0){
                if((drop = tiles.drop(pos)) != BlockTable.NONE){
                    quadrant(ores, drop, qx, qy).add(pos);
                    oreCounts[drop]++;
                }
            }else if((drop = tiles.wallDrop(pos)) != BlockTable.NONE){
                quadrant(wallOres, drop, qx, qy).add(pos);
                wallOreCounts[drop]++;
            }
        }
        loaded = true;
    }

    /** {@code addIndex(tile)}'s ores, for each tile linked to the tile's block (Tile.java:483). */
    @Override
    public void buildingChanged(int pos){
        if(!loaded) return;
        TileGrid tiles = world.tiles();
        int size = tiles.table().size(tiles.block(pos));
        if(size > 1){
            int offset = Footprint.offset(size), x = tiles.x(pos), y = tiles.y(pos);
            for(int dx = 0; dx < size; dx++){
                for(int dy = 0; dy < size; dy++){
                    int wx = x + dx + offset, wy = y + dy + offset;
                    if(tiles.inBounds(wx, wy)) index(tiles.pos(wx, wy));
                }
            }
        }else{
            index(pos);
        }
    }

    private void index(int pos){
        TileGrid tiles = world.tiles();
        int drop = tiles.drop(pos), wallDrop = tiles.wallDrop(pos);
        if(drop == BlockTable.NONE && wallDrop == BlockTable.NONE) return;
        int qx = tiles.x(pos) / QUADRANT, qy = tiles.y(pos) / QUADRANT;
        if(tiles.block(pos) == 0){
            if(drop != BlockTable.NONE && quadrant(ores, drop, qx, qy).addUnique(pos)) oreCounts[drop]++;
            if(wallDrop != BlockTable.NONE && listed(wallOres, wallDrop, qx, qy) && wallOres[wallDrop][qx * quadHeight + qy].removeValue(pos)){
                wallOreCounts[wallDrop]--;
            }
        }else{
            if(wallDrop != BlockTable.NONE && quadrant(wallOres, wallDrop, qx, qy).addUnique(pos)) wallOreCounts[wallDrop]++;
            if(drop != BlockTable.NONE && listed(ores, drop, qx, qy) && ores[drop][qx * quadHeight + qy].removeValue(pos)){
                oreCounts[drop]--;
            }
        }
    }

    private boolean listed(IntSeq[][] index, int item, int qx, int qy){
        return index[item] != null && index[item][qx * quadHeight + qy] != null;
    }

    private IntSeq quadrant(IntSeq[][] index, int item, int qx, int qy){
        if(index[item] == null) index[item] = new IntSeq[quadWidth * quadHeight];
        IntSeq list = index[item][qx * quadHeight + qy];
        return list != null ? list : (index[item][qx * quadHeight + qy] = new IntSeq(false, 16));
    }

    private void require(){
        if(!loaded) loaded();
    }

    // endregion
    // region queries

    /** {@code indexer.hasOre(item)}: the map has the item as a floor ore. */
    public boolean hasOre(int item){
        require();
        return item >= 0 && item < oreCounts.length && oreCounts[item] > 0;
    }

    /** {@code indexer.hasWallOre(item)}: the map has the item as a wall ore. */
    public boolean hasWallOre(int item){
        require();
        return item >= 0 && item < wallOreCounts.length && wallOreCounts[item] > 0;
    }

    /**
     * {@code indexer.findClosestOre(x, y, item)}: of each quadrant's first floor ore that is still uncovered, the
     * closest to (x, y), quadrants by column then row, the first of equals; -1 for none.
     */
    public int findClosestOre(float x, float y, int item){
        return closest(ores, x, y, item, true);
    }

    /** {@code indexer.findClosestWallOre(x, y, item)}: as {@link #findClosestOre}, for each quadrant's first wall ore still covered. */
    public int findClosestWallOre(float x, float y, int item){
        return closest(wallOres, x, y, item, false);
    }

    private int closest(IntSeq[][] index, float x, float y, int item, boolean uncovered){
        require();
        if(item < 0 || item >= index.length || index[item] == null) return -1;
        TileGrid tiles = world.tiles();
        int closest = -1;
        float min = 0f;
        for(int qx = 0; qx < quadWidth; qx++){
            for(int qy = 0; qy < quadHeight; qy++){
                IntSeq list = index[item][qx * quadHeight + qy];
                if(list == null || list.size == 0) continue;
                int pos = list.first();
                if((tiles.block(pos) == 0) != uncovered) continue;
                float dst = Mathf.dst2(x, y, tiles.x(pos) * TILESIZE, tiles.y(pos) * TILESIZE);
                if(closest == -1 || dst < min){
                    closest = pos;
                    min = dst;
                }
            }
        }
        return closest;
    }

    // endregion
    // region state

    @Override
    public WorldPart copy(CopyContext context){
        var out = new OreIndex();
        out.world = context.world();
        out.loaded = loaded;
        out.quadWidth = quadWidth;
        out.quadHeight = quadHeight;
        out.ores = copy(ores);
        out.wallOres = copy(wallOres);
        out.oreCounts = oreCounts.clone();
        out.wallOreCounts = wallOreCounts.clone();
        return out;
    }

    private static IntSeq[][] copy(IntSeq[][] index){
        var out = new IntSeq[index.length][];
        for(int i = 0; i < index.length; i++){
            if(index[i] == null) continue;
            out[i] = new IntSeq[index[i].length];
            for(int q = 0; q < index[i].length; q++) if(index[i][q] != null) out[i][q] = index[i][q].copy();
        }
        return out;
    }

    @Override
    public void hash(Hasher hasher){
        hasher.bool(loaded).i(quadWidth).i(quadHeight);
        hash(hasher, ores);
        hash(hasher, wallOres);
        hasher.ints(oreCounts, 0, oreCounts.length).ints(wallOreCounts, 0, wallOreCounts.length);
    }

    private static void hash(Hasher hasher, IntSeq[][] index){
        hasher.i(index.length);
        for(int i = 0; i < index.length; i++){
            if(index[i] == null) continue;
            for(int q = 0; q < index[i].length; q++){
                IntSeq list = index[i][q];
                if(list != null) hasher.i(i).i(q).ints(list.items, 0, list.size);
            }
        }
    }

    @Override
    public String section(){
        return "ores";
    }

    @Override
    public void write(World world, Writes out){
        out.i(VERSION);
        out.bool(loaded);
        out.i(quadWidth);
        out.i(quadHeight);
        write(out, ores, oreCounts);
        write(out, wallOres, wallOreCounts);
    }

    private static void write(Writes out, IntSeq[][] index, int[] counts){
        out.i(index.length);
        for(int item = 0; item < index.length; item++){
            out.i(counts[item]);
            out.bool(index[item] != null);
            if(index[item] == null) continue;
            for(IntSeq list : index[item]){
                out.i(list == null ? -1 : list.size);
                if(list != null) for(int i = 0; i < list.size; i++) out.i(list.get(i));
            }
        }
    }

    @Override
    public void read(World world, Reads in){
        int version = in.i();
        if(version != VERSION) throw new CheckpointException("ores section version " + version + ", expected " + VERSION);
        init(world);
        loaded = in.bool();
        quadWidth = in.i();
        quadHeight = in.i();
        int items = in.i();
        ores = new IntSeq[items][];
        oreCounts = new int[items];
        read(in, ores, oreCounts);
        if(in.i() != items) throw new CheckpointException("the ores section's item counts differ");
        wallOres = new IntSeq[items][];
        wallOreCounts = new int[items];
        read(in, wallOres, wallOreCounts);
    }

    private void read(Reads in, IntSeq[][] index, int[] counts){
        for(int item = 0; item < index.length; item++){
            counts[item] = in.i();
            if(!in.bool()) continue;
            index[item] = new IntSeq[quadWidth * quadHeight];
            for(int q = 0; q < index[item].length; q++){
                int size = in.i();
                if(size < 0) continue;
                var list = new IntSeq(false, Math.max(16, size));
                for(int i = 0; i < size; i++) list.add(in.i());
                index[item][q] = list;
            }
        }
    }

    // endregion
}
