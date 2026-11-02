package com.xpdustry.shard.compat.v160.filters;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;

/**
 * A loading world's tiles as generation filters see them: vanilla's {@code Tiles} and the {@code Tile} calls the
 * filters make, over Shard's grid. Writes go through {@link Buildings}, which the loader has in its load state, so no
 * events, journal or proximity updates happen, as in vanilla while {@code world.isGenerating()}.
 *
 * <p>The block predicates the filters read ({@code isFloor()}, {@code hasSurface()}, {@code instanceof OreBlock}…) are
 * tabled per block id once per load.
 */
public final class FilterGrid{
    private static final KindKey ORE = KindKey.of("ore-block"), CORE = KindKey.of("core-block"), STORAGE = KindKey.of("storage-block");

    final World world;
    final TileGrid tiles;
    final BlockTable table;
    final ContentEpoch content;
    /** The rules of the file being loaded: vanilla's {@code state.rules} while the map loads. */
    final Rules rules;
    /** Vanilla's {@code Mathf.rand}: the world's gameplay generator. */
    final Rand rand;
    /** The {@code spawn} overlay's id. */
    final int spawn;
    /** The file's content header, which still maps vanilla's {@code content.block(id)} while the filters run. */
    private final ContentHeader.IdMapping ids;
    private final boolean[] ore, needsSurface, hasSurface, core, storage;

    FilterGrid(World world, Rules rules, ContentHeader.IdMapping ids){
        this.world = world;
        this.ids = ids;
        this.tiles = world.tiles();
        this.table = tiles.table();
        this.content = world.content();
        this.rules = rules;
        this.rand = world.rand();
        this.spawn = content.id(ContentType.BLOCK, "spawn");
        int blocks = content.blocks();
        ore = new boolean[blocks];
        needsSurface = new boolean[blocks];
        hasSurface = new boolean[blocks];
        core = new boolean[blocks];
        storage = new boolean[blocks];
        for(int id = 0; id < blocks; id++){
            BlockDef block = content.block(id);
            if(!block.baked()) continue;
            ore[id] = block.isA(ORE);
            core[id] = block.isA(CORE);
            storage[id] = block.isA(STORAGE);
            if(table.isFloor(id)){
                needsSurface[id] = block.fields().b("needsSurface");
                // Floor.hasSurface()
                hasSurface[id] = !table.isLiquid(id) && !table.solid(id);
            }
        }
    }

    /**
     * Vanilla's {@code content.block(id)} during the filters. Vanilla quirk: they run inside {@code readMap}, before
     * {@code SaveIO.load} clears the temporary mapper {@code readContentHeader} set, so a current id is read as the
     * file's: for a map saved with other ids, the buffered filters and the median filters write other blocks.
     */
    int byId(int id){
        int current = ids.toCurrent(ContentType.BLOCK, id);
        if(current < 0) throw new MsavException("a filter wrote block id " + id + ", which the map's content header maps to null: vanilla's load fails on it too");
        return current;
    }

    public int width(){
        return tiles.width();
    }

    public int height(){
        return tiles.height();
    }

    public int size(){
        return tiles.size();
    }

    public int x(int pos){
        return tiles.x(pos);
    }

    public int y(int pos){
        return tiles.y(pos);
    }

    public int pos(int x, int y){
        return tiles.pos(x, y);
    }

    /** The definition of block {@code id}. */
    public BlockDef def(int id){
        return content.block(id);
    }

    // reads (Tile)

    public int floor(int pos){
        return tiles.floor(pos);
    }

    public int overlay(int pos){
        return tiles.overlay(pos);
    }

    public int block(int pos){
        return tiles.block(pos);
    }

    /** {@code Tile.team()}: the building's team, or derelict. */
    public int team(int pos){
        return tiles.team(pos);
    }

    /** {@code Tile.isCenter()}: no building, or the building's origin. */
    public boolean isCenter(int pos){
        Building building = world.buildings().at(pos);
        return building == null || building.tile() == pos;
    }

    /** {@code Tile.solid()}. */
    public boolean solid(int pos){
        return tiles.solid(pos);
    }

    /** {@code Tile.synthetic()}. */
    public boolean synthetic(int pos){
        return tiles.synthetic(pos);
    }

    // block predicates

    /** {@code Block.synthetic()}: {@code update || destructible}. */
    public boolean isSynthetic(int id){
        return table.synthetic(id);
    }

    public boolean isFloor(int id){
        return table.isFloor(id);
    }

    public boolean isOverlay(int id){
        return table.isOverlay(id);
    }

    public boolean isStatic(int id){
        return table.isStatic(id);
    }

    /** {@code Block.solid}. */
    public boolean isSolid(int id){
        return table.solid(id);
    }

    /** {@code Floor.isDeep()}. */
    public boolean isDeep(int id){
        return table.isDeep(id);
    }

    /** {@code instanceof OreBlock}. */
    public boolean isOre(int id){
        return ore[id];
    }

    /** {@code Floor.needsSurface}; false for blocks that aren't floors. */
    public boolean needsSurface(int id){
        return needsSurface[id];
    }

    /** {@code Floor.hasSurface()}: neither liquid nor solid; false for blocks that aren't floors. */
    public boolean hasSurface(int id){
        return hasSurface[id];
    }

    /** {@code instanceof CoreBlock}. */
    public boolean isCore(int id){
        return core[id];
    }

    /** {@code instanceof StorageBlock}, cores included. */
    public boolean isStorage(int id){
        return storage[id];
    }

    // writes (Tile)

    /** {@code Tile.setFloor(floor)}: a building on the tile hears of it. */
    public void setFloor(int pos, int floor){
        requireFloor(floor, "floor");
        world.buildings().setFloor(tiles.x(pos), tiles.y(pos), content.block(floor), Cause.SERVER);
    }

    /** {@code Tile.setOverlay(block)}, whose cast to {@code Floor} fails vanilla's load for any other block. */
    public void setOverlay(int pos, int overlay){
        if(tiles.overlay(pos) == overlay) return;
        requireFloor(overlay, "overlay");
        world.buildings().setOverlay(tiles.x(pos), tiles.y(pos), content.block(overlay), Cause.SERVER);
    }

    /**
     * {@code Tile.setBlock(block)}: derelict, rotation 0. Setting a tile without a building to the block it already
     * has changes nothing while the map loads, so it is skipped.
     */
    public void setBlock(int pos, int block){
        if(tiles.block(pos) == block && tiles.build(pos) == -1) return;
        world.buildings().setBlock(tiles.x(pos), tiles.y(pos), content.block(block), 0, 0, Cause.SERVER);
    }

    /** {@code Tile.remove()}: air where the tile's building, or block, was. */
    public void remove(int pos){
        world.buildings().setBlock(tiles.x(pos), tiles.y(pos), content.block(0), 0, 0, Cause.SERVER);
    }

    /** The building at {@code pos}, or null. */
    public Building building(int pos){
        return world.buildings().at(pos);
    }

    /** {@code Tile.setPackedData}. */
    void data(int pos, byte data, byte floorData, byte overlayData, int extraData){
        tiles.data(pos, data);
        tiles.floorData(pos, floorData);
        tiles.overlayData(pos, overlayData);
        tiles.extraData(pos, extraData);
    }

    /** Vanilla's cast to {@code Floor}, which fails its load for any other block. */
    void requireFloor(int id, String what){
        if(!table.isFloor(id)) throw new MsavException("a generation filter sets the " + what + " of a tile to " + content.block(id).name()
            + ", which is not a floor: vanilla's load fails on it too");
    }
}
