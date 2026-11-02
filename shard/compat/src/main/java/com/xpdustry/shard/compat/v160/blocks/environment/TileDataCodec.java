package com.xpdustry.shard.compat.v160.blocks.environment;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.sim.tile.*;

/**
 * The per-tile data bytes environment blocks keep ({@code Tile.data}, {@code floorData}, {@code overlayData},
 * {@code extraData}) in their v160.7 byte forms:
 * <ul>
 *   <li>the 7-byte record of a map section tile whose {@code shouldSaveData()} is true ({@code SaveVersion.writeMap},
 *   v7 and later): {@code data}, {@code floorData}, {@code overlayData} as bytes, then {@code extraData} as a
 *   big-endian int;</li>
 *   <li>vanilla's {@code PackedTileData} long ({@code Tile.getPackedData()}): {@code extraData} in bits 0–31, then
 *   one byte each for {@code data}, {@code floorData} and {@code overlayData}.</li>
 * </ul>
 * The msav map section itself is W1.7's; it calls these for the tiles that carry data.
 */
public final class TileDataCodec{
    /** The size of the map-section record. */
    public static final int SAVED_BYTES = 7;

    /** Writes the tile's data record, as {@code SaveVersion.java:255-258}. */
    public static void write(Writes out, TileGrid tiles, int pos){
        out.b(tiles.data(pos));
        out.b(tiles.floorData(pos));
        out.b(tiles.overlayData(pos));
        out.i(tiles.extraData(pos));
    }

    /** Writes a data record of packed data ({@link #pack}). */
    public static void write(Writes out, long packed){
        out.b((byte)(packed >>> 32));
        out.b((byte)(packed >>> 40));
        out.b((byte)(packed >>> 48));
        out.i((int)packed);
    }

    /** Reads a data record as packed data ({@link #pack}). */
    public static long read(Reads in){
        byte data = in.b(), floorData = in.b(), overlayData = in.b();
        return pack(in.i(), data, floorData, overlayData);
    }

    /** Reads a data record into the tile, as {@code SaveVersion.java:337-361}. */
    public static void read(Reads in, TileGrid tiles, int pos){
        byte data = in.b(), floorData = in.b(), overlayData = in.b();
        int extraData = in.i();
        tiles.data(pos, data);
        tiles.floorData(pos, floorData);
        tiles.overlayData(pos, overlayData);
        tiles.extraData(pos, extraData);
    }

    /** {@code PackedTileData.get(extraData, data, floorData, overlayData)}: each field masked into place. */
    public static long pack(int extraData, byte data, byte floorData, byte overlayData){
        return (extraData & 0xffffffffL) | (data & 0xffL) << 32 | (floorData & 0xffL) << 40 | (overlayData & 0xffL) << 48;
    }

    /** {@code Tile.getPackedData()}. */
    public static long packed(TileGrid tiles, int pos){
        return pack(tiles.extraData(pos), tiles.data(pos), tiles.floorData(pos), tiles.overlayData(pos));
    }

    /** {@code Tile.setPackedData(packed)}. */
    public static void unpack(long packed, TileGrid tiles, int pos){
        tiles.extraData(pos, (int)packed);
        tiles.data(pos, (byte)(packed >>> 32));
        tiles.floorData(pos, (byte)(packed >>> 40));
        tiles.overlayData(pos, (byte)(packed >>> 48));
    }

    private TileDataCodec(){}
}
