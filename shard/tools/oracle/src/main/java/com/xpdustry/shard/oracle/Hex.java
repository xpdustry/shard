package com.xpdustry.shard.oracle;

import java.util.*;

/** Lowercase hex, as used in the research notes. */
public final class Hex{
    private static final HexFormat format = HexFormat.of();

    public static String of(byte[] bytes){
        return format.formatHex(bytes);
    }

    public static String of(byte[] bytes, int from, int to){
        return format.formatHex(bytes, from, to);
    }

    /** Parses hex, ignoring whitespace, so dumps from the notes can be pasted as is. */
    public static byte[] parse(String hex){
        return format.parseHex(hex.replaceAll("\\s+", ""));
    }

    private Hex(){}
}
