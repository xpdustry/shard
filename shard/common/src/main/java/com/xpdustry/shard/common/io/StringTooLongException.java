package com.xpdustry.shard.common.io;

/**
 * {@link Reads#str(int)} read a length over its limit. Arc throws a bare {@code RuntimeException} with the same
 * message; this subclass lets callers catch the limit alone.
 */
public final class StringTooLongException extends RuntimeException{
    private final int length, limit;

    public StringTooLongException(int length, int limit){
        //quirk kept from Arc: the message names the limit, not the length
        super("String too long: " + limit);
        this.length = length;
        this.limit = limit;
    }

    /** The encoded length the string's u16 gave. */
    public int length(){
        return length;
    }

    public int limit(){
        return limit;
    }
}
