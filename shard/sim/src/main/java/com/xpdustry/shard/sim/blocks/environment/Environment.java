package com.xpdustry.shard.sim.blocks.environment;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.tile.*;

import java.util.*;

/**
 * Vanilla's environment block classes ({@code world/blocks/environment/*}) as kinds, keyed as the content schema keys
 * them: the class name in kebab-case, which is {@code BlockDef.kind()}. They are data: none has a building
 * ({@code update} and {@code destructible} are false), so they are {@link TileKinds}, and their behaviour is the
 * predicates {@link TileGrid} keeps from each block's fields (solidity, depth, liquid, drown time, speed, status,
 * damage, attributes, drops, darkness) plus the few rules of this package. They are exactly the vanilla schema's
 * building-less block kinds; {@code TiledFloor}, {@code TiledWall} and {@code WobbleProp} have no key because no
 * v160.7 content uses them, so the schema has no such kind.
 */
@Register
public final class Environment implements TileKinds{
    /** Air, block 0: the empty floor, overlay and block. */
    public static final KindKey AIR_BLOCK = KindKey.of("air-block");
    /** A floor: drown time, speed, status, damage, liquid drop and attributes for what stands on it. */
    public static final KindKey FLOOR = KindKey.of("floor");
    /** A floor that is shallow liquid over ground: liquid, but not deep. */
    public static final KindKey SHALLOW_LIQUID = KindKey.of("shallow-liquid");
    /** The void outside a sector: solid, nothing placeable on it. */
    public static final KindKey EMPTY_FLOOR = KindKey.of("empty-floor");
    /** A floor tinted by its tile's {@code extraData}. */
    public static final KindKey COLORED_FLOOR = KindKey.of("colored-floor");
    /** A 3×3 vent drawn on floor tiles: see {@link SteamVents}. */
    public static final KindKey STEAM_VENT = KindKey.of("steam-vent");
    /** An overlay floor: drawn over the floor; ores are overlays. */
    public static final KindKey OVERLAY_FLOOR = KindKey.of("overlay-floor");
    /** An ore overlay: drops its item when mined. */
    public static final KindKey ORE_BLOCK = KindKey.of("ore-block");
    /** The enemy spawn marker, an overlay. */
    public static final KindKey SPAWN_BLOCK = KindKey.of("spawn-block");
    /** Editor tool that clears overlays. */
    public static final KindKey REMOVE_ORE = KindKey.of("remove-ore");
    /** Editor tool that clears walls. */
    public static final KindKey REMOVE_WALL = KindKey.of("remove-wall");
    /** An overlay of letters. */
    public static final KindKey CHARACTER_OVERLAY = KindKey.of("character-overlay");
    /** An overlay of runes. */
    public static final KindKey RUNE_OVERLAY = KindKey.of("rune-overlay");
    /** A static wall: solid, static, darkened at map load. */
    public static final KindKey STATIC_WALL = KindKey.of("static-wall");
    /** A static wall tinted by {@code extraData}, darkened only when {@code extraData} says so. */
    public static final KindKey COLORED_WALL = KindKey.of("colored-wall");
    /** A static tree, a static wall. */
    public static final KindKey STATIC_TREE = KindKey.of("static-tree");
    /** A cliff: solid and static, but it doesn't fill its tile, so it isn't darkened. */
    public static final KindKey CLIFF = KindKey.of("cliff");
    /** A tree: solid, not static. */
    public static final KindKey TREE_BLOCK = KindKey.of("tree-block");
    /** A prop (boulders): breakable, always replaced by placement. */
    public static final KindKey PROP = KindKey.of("prop");
    /** A prop drawn into the static layer. */
    public static final KindKey STATIC_PROP = KindKey.of("static-prop");
    /** A tall solid block (crystal blocks), not a prop. */
    public static final KindKey TALL_BLOCK = KindKey.of("tall-block");
    /** A sea bush prop. */
    public static final KindKey SEA_BUSH = KindKey.of("sea-bush");
    /** A seaweed prop. */
    public static final KindKey SEAWEED = KindKey.of("seaweed");

    private static final List<KindKey> KEYS = List.of(AIR_BLOCK, FLOOR, SHALLOW_LIQUID, EMPTY_FLOOR, COLORED_FLOOR,
        STEAM_VENT, OVERLAY_FLOOR, ORE_BLOCK, SPAWN_BLOCK, REMOVE_ORE, REMOVE_WALL, CHARACTER_OVERLAY, RUNE_OVERLAY,
        STATIC_WALL, COLORED_WALL, STATIC_TREE, CLIFF, TREE_BLOCK, PROP, STATIC_PROP, TALL_BLOCK, SEA_BUSH, SEAWEED);

    @Override
    public List<KindKey> keys(){
        return KEYS;
    }
}
