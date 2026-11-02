package com.xpdustry.shard.compat.v160.world;

import com.xpdustry.shard.content.*;

import java.io.*;
import java.nio.charset.*;
import java.util.*;

/**
 * The save body vanilla writes for a building of each block in the state {@code tile.setBlock} makes it in: its
 * {@code version()} and {@code write()} bytes, measured on vanilla ({@code FreshBodiesOracleTest}). A
 * {@link com.xpdustry.shard.sim.building.PassthroughBuilding} placed live (a world processor's {@code setblock},
 * construction) has no save bytes, and Shard doesn't simulate its kind, so it is written with these: the state vanilla's
 * building has until it updates. Vanilla quirk kept: {@code landing-pad}'s body holds a value its constructor draws, the
 * one vanilla draws from a generator seeded with 0.
 */
public final class FreshBodies{
    /** A block's building version and body. */
    public record Body(int revision, byte[] bytes){
        public Body{
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes(){
            return bytes.clone();
        }
    }

    private static final Map<String, Body> BODIES = load();

    private FreshBodies(){
    }

    /** The fresh body of {@code block}'s building, or null for a block vanilla v160.7 doesn't have. */
    public static Body of(BlockDef block){
        return BODIES.get(block.name());
    }

    private static Map<String, Body> load(){
        String text;
        try(InputStream in = FreshBodies.class.getResourceAsStream("fresh-bodies.txt")){
            text = new String(Objects.requireNonNull(in, "fresh-bodies.txt").readAllBytes(), StandardCharsets.UTF_8);
        }catch(IOException e){
            throw new UncheckedIOException(e);
        }
        var out = new TreeMap<String, Body>();
        for(String line : text.split("\n")){
            if(line.isBlank() || line.startsWith("#")) continue;
            String[] parts = line.split(" ");
            out.put(parts[0], new Body(Integer.parseInt(parts[1]), parts[2].equals("-") ? new byte[0] : HexFormat.of().parseHex(parts[2])));
        }
        return Collections.unmodifiableMap(out);
    }
}
