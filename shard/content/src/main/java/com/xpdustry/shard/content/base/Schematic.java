package com.xpdustry.shard.content.base;

import com.xpdustry.shard.content.*;

import java.util.*;

/**
 * A schematic as vanilla's {@code Schematics.read} leaves it (Schematics.java:570): its size, its tags and its tiles in
 * file order, tiles whose block is unknown or legacy left out (vanilla reads them as air and drops them).
 *
 * @param name the file it was read from, for messages
 */
public record Schematic(String name, int width, int height, Map<String, String> tags, List<Tile> tiles){
    public Schematic{
        tags = Collections.unmodifiableMap(new LinkedHashMap<>(tags));
        tiles = List.copyOf(tiles);
    }

    /**
     * One of its tiles, vanilla's {@code Schematic.Stile}: a block at (x, y), the bottom-left corner being (0, 0), with
     * its config and rotation. The config is what {@code TypeIO.readObject} gives: null, a boxed {@link Integer},
     * {@link Long}, {@link Float}, {@link Double} or {@link Boolean}, a {@link String}, a {@link ContentValue}, an
     * {@code int[]} ({@code IntSeq}), a {@link com.xpdustry.shard.common.geom.Point2} or an array of them, or a
     * {@code byte[]}.
     */
    public record Tile(BlockDef block, int x, int y, Object config, int rotation){
    }

    /** A content config ({@code TypeIO}'s type 5): vanilla's {@code ContentType} ordinal and the content's id. */
    public record ContentValue(int vanillaType, int id){
    }
}
