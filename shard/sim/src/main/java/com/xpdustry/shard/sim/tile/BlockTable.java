package com.xpdustry.shard.sim.tile;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.environment.*;

import java.util.*;

/**
 * Every block's tile-layer properties as dense arrays indexed by wire id, built once per {@link ContentEpoch} from its
 * {@link BlockDef}s ({@link FloorDef}s for floors) and immutable, so the worlds and forks that pin an epoch share it.
 * {@link TileGrid} keeps its predicate bitsets from it and answers the per-floor queries (drown time, speed, status,
 * attributes…) through it.
 *
 * <p>Derived rules, as vanilla's {@code Block}: {@code hasBuilding() = update || destructible},
 * {@code synthetic() = update || destructible}, {@code isMultiblock() = size > 1}, {@code Floor.isDeep() =
 * drownTime > 0}, {@code Floor.damages() = damageTaken > 0 || status.damage > 0}. Darkness follows the block's kind
 * where vanilla overrides {@code isDarkened}: {@code colored-wall} and {@code logic-block}, and the kinds that extend
 * them. A floor field of a block that isn't a floor reads as vanilla's {@code Floor} initializer.
 *
 * <p>A hand-built definition (test content, not {@linkplain ContentDef#baked() baked}) reads as a plain, non-solid,
 * destructible building block, as the foundation treated every block; block 0 (air) never has a building.
 */
public final class BlockTable{
    /** No item, liquid, status or team. */
    public static final int NONE = -1;
    /** The kind keys whose {@code isDarkened} differs from {@code Block}'s. */
    public static final KindKey COLORED_WALL = KindKey.of("colored-wall"), LOGIC_BLOCK = KindKey.of("logic-block");

    static final int
        SOLID = 1, SOLIDIFES = 1 << 1, DESTRUCTIBLE = 1 << 2, BREAKABLE = 1 << 3, ALWAYS_REPLACE = 1 << 4,
        TEAM_PASSABLE = 1 << 5, FILLS_TILE = 1 << 6, FORCE_DARK = 1 << 7, PRIVILEGED = 1 << 8, STATIC = 1 << 9,
        SAVE_DATA = 1 << 10, UPDATE = 1 << 11, FLOOR = 1 << 12, OVERLAY = 1 << 13, LIQUID = 1 << 14, WALL_ORE = 1 << 15,
        PLAYER_UNMINEABLE = 1 << 16, PLACEABLE_ON = 1 << 17, DEEP = 1 << 18, DAMAGES = 1 << 19;

    /** How {@code isDarkened(tile)} is decided. */
    static final byte DARK_BLOCK = 0, DARK_COLORED_WALL = 1, DARK_LOGIC = 2;

    private final BlockDef[] defs;
    private final int[] flags, status, itemDrop, liquidDrop, forceTeam;
    private final byte[] size, darkness;
    private final float[] drownTime, speedMultiplier, dragMultiplier, damageTaken, statusDuration, liquidMultiplier;
    private final float[] attributes;

    private BlockTable(ContentEpoch content){
        int n = content.blocks();
        defs = new BlockDef[n];
        flags = new int[n];
        status = new int[n];
        itemDrop = new int[n];
        liquidDrop = new int[n];
        forceTeam = new int[n];
        size = new byte[n];
        darkness = new byte[n];
        drownTime = new float[n];
        speedMultiplier = new float[n];
        dragMultiplier = new float[n];
        damageTaken = new float[n];
        statusDuration = new float[n];
        liquidMultiplier = new float[n];
        attributes = new float[n * Attributes.COUNT];
        for(int id = 0; id < n; id++){
            BlockDef block = content.block(id);
            if(block.id() != id) throw new IllegalArgumentException("block " + block.name() + " has id " + block.id() + " at " + id);
            if(block.size() < 1 || block.size() > 127) throw new IllegalArgumentException("block " + block.name() + " has size " + block.size());
            defs[id] = block;
            size[id] = (byte)block.size();
            // Floor's initializers, which non-floors keep
            forceTeam[id] = itemDrop[id] = liquidDrop[id] = NONE;
            speedMultiplier[id] = dragMultiplier[id] = liquidMultiplier[id] = 1f;
            statusDuration[id] = 60f;
            if(block.baked()) read(id, block);
            else flags[id] = flag(id != 0, DESTRUCTIBLE) | FILLS_TILE | PLACEABLE_ON | flag(block.update(), UPDATE);
            darkness[id] = darkness(block);
        }
    }

    private void read(int id, BlockDef block){
        int bits = flag(block.solid(), SOLID) | flag(block.solidifes(), SOLIDIFES) | flag(block.destructible(), DESTRUCTIBLE)
            | flag(block.breakable(), BREAKABLE) | flag(block.alwaysReplace(), ALWAYS_REPLACE)
            | flag(block.teamPassable(), TEAM_PASSABLE) | flag(block.fillsTile(), FILLS_TILE) | flag(block.forceDark(), FORCE_DARK)
            | flag(block.privileged(), PRIVILEGED) | flag(block.isStatic(), STATIC) | flag(block.saveData(), SAVE_DATA)
            | flag(block.update(), UPDATE) | flag(block.playerUnmineable(), PLAYER_UNMINEABLE)
            | flag(block.placeableOn(), PLACEABLE_ON);
        if(block instanceof FloorDef floor){
            bits |= FLOOR | flag(floor.isOverlay(), OVERLAY) | flag(floor.isLiquid(), LIQUID) | flag(floor.wallOre(), WALL_ORE)
                | flag(floor.isDeep(), DEEP) | flag(floor.damages(), DAMAGES);
            status[id] = floor.status().id();
            liquidDrop[id] = floor.liquidDrop() == null ? NONE : floor.liquidDrop().id();
            drownTime[id] = floor.drownTime();
            speedMultiplier[id] = floor.speedMultiplier();
            dragMultiplier[id] = floor.dragMultiplier();
            damageTaken[id] = floor.damageTaken();
            statusDuration[id] = floor.statusDuration();
            liquidMultiplier[id] = floor.liquidMultiplier();
        }
        flags[id] = bits;
        forceTeam[id] = block.forceTeam();
        if(block.itemDrop() != null) itemDrop[id] = block.itemDrop().id();
        for(Map.Entry<String, Float> entry : block.attributes().entrySet()){
            int attribute = Attributes.id(entry.getKey());
            if(attribute < 0) throw new IllegalArgumentException("block " + block.name() + " has an unknown attribute " + entry.getKey());
            attributes[id * Attributes.COUNT + attribute] = entry.getValue();
        }
    }

    private static byte darkness(BlockDef block){
        boolean colored = block.baked() ? block.isA(COLORED_WALL) : block.kind().equals(COLORED_WALL);
        boolean logic = block.baked() ? block.isA(LOGIC_BLOCK) : block.kind().equals(LOGIC_BLOCK);
        return colored ? DARK_COLORED_WALL : logic ? DARK_LOGIC : DARK_BLOCK;
    }

    public static BlockTable of(ContentEpoch content){
        return new BlockTable(content);
    }

    private static int flag(boolean value, int bit){
        return value ? bit : 0;
    }

    boolean is(int id, int flag){
        return (flags[id] & flag) != 0;
    }

    public int blocks(){
        return defs.length;
    }

    public BlockDef def(int id){
        return defs[id];
    }

    public int size(int id){
        return size[id];
    }

    /** {@code Block.sizeOffset}: {@code -(size - 1) / 2}, the footprint's offset from its origin tile. */
    public int sizeOffset(int id){
        return Footprint.offset(size[id]);
    }

    public boolean isAir(int id){
        return id == 0;
    }

    public boolean isMultiblock(int id){
        return size[id] > 1;
    }

    public boolean solid(int id){
        return is(id, SOLID);
    }

    public boolean solidifes(int id){
        return is(id, SOLIDIFES);
    }

    public boolean update(int id){
        return is(id, UPDATE);
    }

    public boolean destructible(int id){
        return is(id, DESTRUCTIBLE);
    }

    /** {@code Block.hasBuilding()}: buildings of this block are created. */
    public boolean hasBuilding(int id){
        return is(id, UPDATE | DESTRUCTIBLE);
    }

    /** {@code Block.synthetic()}: placed by someone rather than part of the map. */
    public boolean synthetic(int id){
        return is(id, UPDATE | DESTRUCTIBLE);
    }

    public boolean breakable(int id){
        return is(id, BREAKABLE);
    }

    public boolean alwaysReplace(int id){
        return is(id, ALWAYS_REPLACE);
    }

    public boolean teamPassable(int id){
        return is(id, TEAM_PASSABLE);
    }

    public boolean fillsTile(int id){
        return is(id, FILLS_TILE);
    }

    public boolean forceDark(int id){
        return is(id, FORCE_DARK);
    }

    public boolean privileged(int id){
        return is(id, PRIVILEGED);
    }

    /** {@code Block.isStatic()}: a static wall layer block (static walls, cliffs). */
    public boolean isStatic(int id){
        return is(id, STATIC);
    }

    public boolean saveData(int id){
        return is(id, SAVE_DATA);
    }

    public boolean isFloor(int id){
        return is(id, FLOOR);
    }

    public boolean isOverlay(int id){
        return is(id, OVERLAY);
    }

    public boolean isLiquid(int id){
        return is(id, LIQUID);
    }

    /** {@code Floor.isDeep()}. */
    public boolean isDeep(int id){
        return is(id, DEEP);
    }

    /** {@code Floor.damages()}. */
    public boolean damages(int id){
        return is(id, DAMAGES);
    }

    public boolean wallOre(int id){
        return is(id, WALL_ORE);
    }

    public boolean playerUnmineable(int id){
        return is(id, PLAYER_UNMINEABLE);
    }

    public boolean placeableOn(int id){
        return is(id, PLACEABLE_ON);
    }

    public int forceTeam(int id){
        return forceTeam[id];
    }

    public int itemDrop(int id){
        return itemDrop[id];
    }

    public int liquidDrop(int id){
        return liquidDrop[id];
    }

    public int status(int id){
        return status[id];
    }

    public float statusDuration(int id){
        return statusDuration[id];
    }

    public float drownTime(int id){
        return drownTime[id];
    }

    public float speedMultiplier(int id){
        return speedMultiplier[id];
    }

    public float dragMultiplier(int id){
        return dragMultiplier[id];
    }

    public float damageTaken(int id){
        return damageTaken[id];
    }

    public float liquidMultiplier(int id){
        return liquidMultiplier[id];
    }

    /** {@code block.attributes.get(attribute)}, by {@link Attributes} id; 0 for an id outside them. */
    public float attribute(int id, int attribute){
        return attribute >= 0 && attribute < Attributes.COUNT ? attributes[id * Attributes.COUNT + attribute] : 0f;
    }

    byte darkness(int id){
        return darkness[id];
    }
}
