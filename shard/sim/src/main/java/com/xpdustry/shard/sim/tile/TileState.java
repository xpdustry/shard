package com.xpdustry.shard.sim.tile;

/**
 * A tile's state packed into a {@code long}, as {@link TileGrid#packed(int)} returns it and
 * {@link TileGrid#changes(TileChangeConsumer)} reports the old state of a changed tile:
 *
 * <pre>
 * bits  0-15  floor id          bits 32-47  block id
 * bits 16-31  overlay id        bits 48-55  team id
 * bits 56-63  flags: solid, legSolid, deep, liquid, static, building (see the FLAG_ constants)
 * </pre>
 */
public final class TileState{
    public static final int FLAG_SOLID = 1, FLAG_LEG_SOLID = 1 << 1, FLAG_DEEP = 1 << 2, FLAG_LIQUID = 1 << 3,
        FLAG_STATIC = 1 << 4, FLAG_BUILDING = 1 << 5;

    public static long pack(int floor, int overlay, int block, int team, int flags){
        return (floor & 0xffffL) | (overlay & 0xffffL) << 16 | (block & 0xffffL) << 32 | (team & 0xffL) << 48
            | (flags & 0xffL) << 56;
    }

    public static int floor(long state){
        return (int)(state & 0xffff);
    }

    public static int overlay(long state){
        return (int)(state >>> 16 & 0xffff);
    }

    public static int block(long state){
        return (int)(state >>> 32 & 0xffff);
    }

    public static int team(long state){
        return (int)(state >>> 48 & 0xff);
    }

    public static int flags(long state){
        return (int)(state >>> 56 & 0xff);
    }

    public static boolean solid(long state){
        return (flags(state) & FLAG_SOLID) != 0;
    }

    public static boolean legSolid(long state){
        return (flags(state) & FLAG_LEG_SOLID) != 0;
    }

    public static boolean deep(long state){
        return (flags(state) & FLAG_DEEP) != 0;
    }

    public static boolean liquid(long state){
        return (flags(state) & FLAG_LIQUID) != 0;
    }

    public static boolean isStatic(long state){
        return (flags(state) & FLAG_STATIC) != 0;
    }

    public static boolean building(long state){
        return (flags(state) & FLAG_BUILDING) != 0;
    }

    public static String toString(long state){
        return "floor " + floor(state) + ", overlay " + overlay(state) + ", block " + block(state) + ", team " + team(state)
            + ", flags 0b" + Integer.toBinaryString(flags(state));
    }

    private TileState(){}
}
