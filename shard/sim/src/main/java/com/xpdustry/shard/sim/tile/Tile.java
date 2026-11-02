package com.xpdustry.shard.sim.tile;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.world.*;

import java.util.function.*;

/**
 * One tile of a world, in the shape of vanilla's {@code Tile}: a value over {@link TileGrid}'s columns and the
 * world's {@link Buildings}, cheap to make and to drop. Reads go to the grid; the mutators are the placement
 * primitives of {@link Buildings}, with a {@link Cause}.
 *
 * @param pos {@code y * width + x}
 */
public record Tile(World world, int pos){
    /** The tile at (x, y), or null outside the map ({@code world.tile(x, y)}). */
    public static Tile at(World world, int x, int y){
        TileGrid tiles = world.tiles();
        return tiles.inBounds(x, y) ? new Tile(world, tiles.pos(x, y)) : null;
    }

    /** The tile at a wire position ({@code Point2.pack(x, y)}), or null outside the map. */
    public static Tile ofWire(World world, int wire){
        int pos = world.tiles().fromWire(wire);
        return pos == -1 ? null : new Tile(world, pos);
    }

    private TileGrid tiles(){
        return world.tiles();
    }

    public int x(){
        return tiles().x(pos);
    }

    public int y(){
        return tiles().y(pos);
    }

    /** {@code Tile.pos()}: the wire position. */
    public int wire(){
        return tiles().toWire(pos);
    }

    public float worldx(){
        return x() * Footprint.TILE_SIZE;
    }

    public float worldy(){
        return y() * Footprint.TILE_SIZE;
    }

    /** {@code drawx()}: the centre of this tile's block if this is its origin (vanilla's caveat applies). */
    public float drawx(){
        return worldx() + Footprint.worldOffset(block().size());
    }

    public float drawy(){
        return worldy() + Footprint.worldOffset(block().size());
    }

    public BlockDef floor(){
        return world.content().block(tiles().floor(pos));
    }

    public BlockDef overlay(){
        return world.content().block(tiles().overlay(pos));
    }

    /** The block covering this tile; the linked tiles of a multiblock all hold it. */
    public BlockDef block(){
        return world.content().block(tiles().block(pos));
    }

    public int floorId(){
        return tiles().floor(pos);
    }

    public int overlayId(){
        return tiles().overlay(pos);
    }

    public int blockId(){
        return tiles().block(pos);
    }

    /** {@code Tile.team()}: the building's team, or 0 (derelict). */
    public int team(){
        return tiles().team(pos);
    }

    /** {@code Tile.build}: the building covering this tile, or null. */
    public Building build(){
        return world.buildings().at(pos);
    }

    /** {@code isCenter()}: no building, or this is its origin. */
    public boolean isCenter(){
        Building build = build();
        return build == null || build.tile() == pos;
    }

    public int centerX(){
        Building build = build();
        return build == null ? x() : tiles().x(build.tile());
    }

    public int centerY(){
        Building build = build();
        return build == null ? y() : tiles().y(build.tile());
    }

    /** {@code world.tileBuilding(x, y)}: the building's origin tile, or this one. */
    public Tile center(){
        Building build = build();
        return build == null ? this : new Tile(world, build.tile());
    }

    public Tile nearby(int dx, int dy){
        return at(world, x() + dx, y() + dy);
    }

    /** The neighbour in {@code rotation} (0 right, 1 up, 2 left, 3 down), or null. */
    public Tile nearby(int rotation){
        return switch(rotation){
            case 0 -> at(world, x() + 1, y());
            case 1 -> at(world, x(), y() + 1);
            case 2 -> at(world, x() - 1, y());
            case 3 -> at(world, x(), y() - 1);
            default -> null;
        };
    }

    public Building nearbyBuild(int rotation){
        return switch(rotation){
            case 0 -> world.buildings().at(x() + 1, y());
            case 1 -> world.buildings().at(x(), y() + 1);
            case 2 -> world.buildings().at(x() - 1, y());
            case 3 -> world.buildings().at(x(), y() - 1);
            default -> null;
        };
    }

    /** {@code relativeTo(cx, cy)}: the rotation from this tile to an adjacent one, or -1. */
    public int relativeTo(int cx, int cy){
        return relativeTo(x(), y(), cx, cy);
    }

    public int relativeTo(Tile other){
        return relativeTo(other.x(), other.y());
    }

    public static int relativeTo(int x, int y, int cx, int cy){
        if(x == cx && y == cy - 1) return 1;
        if(x == cx && y == cy + 1) return 3;
        if(x == cx - 1 && y == cy) return 0;
        if(x == cx + 1 && y == cy) return 2;
        return -1;
    }

    /** {@code absoluteRelativeTo(cx, cy)}, with vanilla's half-tile offset for even sizes. */
    public int absoluteRelativeTo(int cx, int cy){
        int x = x(), y = y();
        if(block().size() % 2 == 1){
            if(Math.abs(x - cx) > Math.abs(y - cy)){
                if(x <= cx - 1) return 0;
                if(x >= cx + 1) return 2;
            }else{
                if(y <= cy - 1) return 1;
                if(y >= cy + 1) return 3;
            }
        }else{
            if(Math.abs(x - cx + 0.5f) > Math.abs(y - cy + 0.5f)){
                if(x + 0.5f <= cx - 1) return 0;
                if(x + 0.5f >= cx + 1) return 2;
            }else{
                if(y + 0.5f <= cy - 1) return 1;
                if(y + 0.5f >= cy + 1) return 3;
            }
        }
        return -1;
    }

    public boolean adjacentTo(Tile other){
        return relativeTo(other) != -1;
    }

    /**
     * {@code getLinkedTiles}: the footprint of this tile's block around this tile, or just this tile. Vanilla quirk
     * kept: around this tile, which is only the block's footprint when called on its origin.
     */
    public void getLinkedTiles(Consumer<Tile> action){
        getLinkedTilesAs(block(), action);
    }

    /** {@code getLinkedTilesAs(block, …)}: the footprint {@code block} would have with its origin here. */
    public void getLinkedTilesAs(BlockDef block, Consumer<Tile> action){
        if(block.size() > 1){
            Footprint.each(tiles(), x(), y(), block.size(), other -> action.accept(new Tile(world, other)));
        }else{
            action.accept(this);
        }
    }

    // predicates

    public boolean solid(){
        return tiles().solid(pos);
    }

    public boolean legSolid(){
        return tiles().legSolid(pos);
    }

    public boolean passable(){
        return tiles().passable(pos);
    }

    public boolean isDeep(){
        return tiles().isDeep(pos);
    }

    public boolean dangerous(){
        return tiles().dangerous(pos);
    }

    public boolean synthetic(){
        return tiles().synthetic(pos);
    }

    public boolean breakable(){
        return tiles().breakable(pos);
    }

    public int staticDarkness(){
        return tiles().staticDarkness(pos);
    }

    /** The item id mined here, or {@link BlockTable#NONE}. */
    public int drop(){
        return tiles().drop(pos);
    }

    public int wallDrop(){
        return tiles().wallDrop(pos);
    }

    // mutators

    /** {@code setBlock(type, team, rotation)}; returns the new building, or null. */
    public Building setBlock(BlockDef type, int team, int rotation, Cause cause){
        return world.buildings().setBlock(x(), y(), type, team, rotation, cause);
    }

    /** {@code setBlock(type)}: derelict, rotation 0. */
    public Building setBlock(BlockDef type, Cause cause){
        return setBlock(type, 0, 0, cause);
    }

    /** {@code remove()} and {@code setAir()}: the block becomes air, removing any building that covers this tile. */
    public void remove(Cause cause){
        setBlock(world.content().block(0), cause);
    }

    public void setFloor(BlockDef floor, Cause cause){
        world.buildings().setFloor(x(), y(), floor, cause);
    }

    public void setOverlay(BlockDef overlay, Cause cause){
        world.buildings().setOverlay(x(), y(), overlay, cause);
    }

    @Override
    public String toString(){
        return floor().name() + ":" + block().name() + ":" + overlay().name() + "[" + x() + "," + y() + "]";
    }
}
