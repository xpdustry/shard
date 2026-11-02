package com.xpdustry.shard.compat.v160.msav;

import com.xpdustry.shard.common.io.*;

/**
 * Skips one {@code TypeIO.readObject} value, so a team plan's config can be kept as raw bytes. Limits and failures
 * are vanilla's for a non-safe read: arrays of at most 200 entries, byte arrays of at most 40,000, no nested arrays.
 */
final class TypeObjects{
    private static final int MAX_ARRAY = 200, MAX_BYTE_ARRAY = 40_000;

    private TypeObjects(){}

    static void skip(Reads in){
        skip(in, true);
    }

    private static void skip(Reads in, boolean allowArrays){
        int type = in.b();
        switch(type){
            case 0 -> {}
            case 1, 3, 12, 17 -> in.skip(need(in, 4));
            case 2, 11 -> in.skip(need(in, 8));
            case 4 -> {
                if(in.b() != 0) in.str();
            }
            case 5, 9 -> {
                in.b();
                in.s();
            }
            case 6 -> {
                arrays(allowArrays);
                int length = limit(in.s(), MAX_ARRAY);
                in.skip(need(in, length * 4));
            }
            case 7, 19 -> in.skip(need(in, 8));
            case 8 -> {
                arrays(allowArrays);
                in.skip(need(in, in.ub() * 4));
            }
            case 10, 15, 20 -> in.b();
            case 13, 23 -> in.s();
            case 14 -> {
                arrays(allowArrays);
                in.skip(need(in, limit(in.i(), MAX_BYTE_ARRAY)));
            }
            case 16 -> {
                arrays(allowArrays);
                in.skip(need(in, limit(in.i(), MAX_ARRAY)));
            }
            case 18 -> {
                arrays(allowArrays);
                in.skip(need(in, limit(in.s(), MAX_ARRAY) * 8));
            }
            case 21 -> in.skip(need(in, limit(in.s(), Integer.MAX_VALUE) * 4));
            case 22 -> {
                arrays(allowArrays);
                int length = limit(in.i(), MAX_ARRAY);
                for(int i = 0; i < length; i++) skip(in, false);
            }
            default -> throw new IllegalArgumentException("Unknown object type: " + type);
        }
    }

    private static void arrays(boolean allowed){
        if(!allowed) throw new IllegalArgumentException("Nested arrays are not allowed");
    }

    /** Vanilla throws past the limit, and on a negative length when it allocates the array. */
    private static int limit(int length, int max){
        if(length > max || length < 0) throw new IllegalArgumentException("Invalid array size: " + length);
        return length;
    }

    /** Fails like a read would when fewer than {@code count} bytes remain. */
    private static int need(Reads in, int count){
        if(count > in.remaining()) in.b(count);
        return count;
    }
}
