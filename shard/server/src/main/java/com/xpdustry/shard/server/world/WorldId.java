package com.xpdustry.shard.server.world;

import com.xpdustry.shard.server.sched.*;

import java.nio.charset.*;
import java.util.regex.*;

/**
 * A world's name, unique on this server: lower-case letters, digits, {@code -} and {@code _}, at most 64 characters,
 * so it is also a safe directory name.
 */
public record WorldId(String name) implements Comparable<WorldId>{
    private static final Pattern FORMAT = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");

    public WorldId{
        if(name == null || !FORMAT.matcher(name).matches()) throw new IllegalArgumentException("bad world id: " + name);
    }

    public static WorldId of(String name){
        return new WorldId(name);
    }

    /** A 64-bit hash of the name (FNV-1a, then mixed), for {@link Stagger}. */
    public long hash(){
        long hash = 0xcbf29ce484222325L;
        for(byte b : name.getBytes(StandardCharsets.UTF_8)){
            hash ^= b & 0xFF;
            hash *= 0x100000001b3L;
        }
        return Stagger.mix(hash);
    }

    @Override
    public int compareTo(WorldId other){
        return name.compareTo(other.name);
    }

    @Override
    public String toString(){
        return name;
    }
}
