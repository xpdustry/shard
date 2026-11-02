package com.xpdustry.shard.content.base;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.content.*;

import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.zip.*;

/**
 * The base-part schematics vanilla bundles for its {@code BaseRegistry} (BaseRegistry.java:46): the files
 * {@code baseparts/<name>} listed one per line in {@code basepartnames}, read in that order. The pack keeps
 * vanilla's files as they are, under {@code content/vanilla/} ({@code :tools:content-extract} copies them).
 *
 * <p>{@link #read(ContentEpoch)} decodes them against a content epoch as {@code Schematics.read} does. Only what the
 * bundled files use is supported: version 1, no {@code contentMap} tag, and the config types listed in
 * {@link Schematic.Tile}; anything else is an {@link IllegalArgumentException}.
 */
public final class BaseParts{
    /** The pack directory the files are in. */
    static final String DIR = "/content/vanilla/";
    /** {@code Schematics.header}. */
    private static final byte[] HEADER = {'m', 's', 'c', 'h'};
    /** {@code Schematics.version}. */
    private static final int VERSION = 1;

    /** {@code SaveFileReader.fallback} (SaveFileReader.java:10): old block names, read as their current ones. */
    static final Map<String, String> FALLBACK = pairs(
        "dart-mech-pad", "legacy-mech-pad", "dart-ship-pad", "legacy-mech-pad",
        "javelin-ship-pad", "legacy-mech-pad", "trident-ship-pad", "legacy-mech-pad",
        "glaive-ship-pad", "legacy-mech-pad", "alpha-mech-pad", "legacy-mech-pad", "tau-mech-pad", "legacy-mech-pad",
        "omega-mech-pad", "legacy-mech-pad", "delta-mech-pad", "legacy-mech-pad",
        "draug-factory", "legacy-unit-factory", "spirit-factory", "legacy-unit-factory",
        "phantom-factory", "legacy-unit-factory", "wraith-factory", "legacy-unit-factory",
        "ghoul-factory", "legacy-unit-factory-air", "revenant-factory", "legacy-unit-factory-air",
        "dagger-factory", "legacy-unit-factory", "crawler-factory", "legacy-unit-factory",
        "titan-factory", "legacy-unit-factory-ground", "fortress-factory", "legacy-unit-factory-ground",
        "mass-conveyor", "payload-conveyor", "vestige", "scepter", "turbine-generator", "steam-generator",
        "rocks", "stone-wall", "sporerocks", "spore-wall", "icerocks", "ice-wall", "dunerocks", "dune-wall",
        "sandrocks", "sand-wall", "shalerocks", "shale-wall", "snowrocks", "snow-wall", "saltrocks", "salt-wall",
        "dirtwall", "dirt-wall", "ignarock", "basalt", "holostone", "dacite", "holostone-wall", "dacite-wall",
        "rock", "boulder", "snowrock", "snow-boulder", "cliffs", "stone-wall", "craters", "crater-stone",
        "deepwater", "deep-water", "water", "shallow-water", "sand", "sand-floor", "slag", "molten-slag",
        "cryofluidmixer", "cryofluid-mixer", "block-forge", "constructor", "block-unloader", "payload-unloader",
        "block-loader", "payload-loader", "thermal-pump", "impulse-pump", "alloy-smelter", "surge-smelter",
        "steam-vent", "rhyolite-vent", "fabricator", "tank-fabricator", "basic-reconstructor", "refabricator"
    );

    private BaseParts(){
    }

    private static Map<String, String> pairs(String... names){
        var out = new HashMap<String, String>();
        for(int i = 0; i < names.length; i += 2) out.put(names[i], names[i + 1]);
        return Map.copyOf(out);
    }

    /** The file names in {@code basepartnames}, in order. */
    public static List<String> names(){
        return Files.NAMES;
    }

    /** The bytes of {@code baseparts/<name>}. */
    public static byte[] bytes(String name){
        byte[] data = Files.DATA.get(name);
        if(data == null) throw new IllegalArgumentException("no base part " + name);
        return data.clone();
    }

    /** Every base part, decoded against {@code content}, in {@link #names()} order. */
    public static List<Schematic> read(ContentEpoch content){
        var out = new ArrayList<Schematic>(Files.NAMES.size());
        for(String name : Files.NAMES) out.add(read(name, Files.DATA.get(name), content));
        return List.copyOf(out);
    }

    /** {@code Schematics.read(input)}, for the bundled files' subset of the format. */
    public static Schematic read(String name, byte[] data, ContentEpoch content){
        try{
            var raw = new ByteArrayInputStream(data);
            for(byte b : HEADER){
                if(raw.read() != b) throw new IllegalArgumentException(name + ": not a schematic file (missing header)");
            }
            int version = raw.read();
            if(version != VERSION) throw new IllegalArgumentException(name + ": schematic version " + version + " isn't supported");
            try(var stream = new DataInputStream(new InflaterInputStream(raw))){
                int width = stream.readShort(), height = stream.readShort();
                var tags = new LinkedHashMap<String, String>();
                int tagCount = stream.readUnsignedByte();
                for(int i = 0; i < tagCount; i++) tags.put(stream.readUTF(), stream.readUTF());
                if(tags.containsKey("contentMap")) throw new IllegalArgumentException(name + ": a contentMap tag isn't supported");
                int length = stream.readUnsignedByte();
                var blocks = new BlockDef[length];
                for(int i = 0; i < length; i++){
                    String blockName = stream.readUTF();
                    BlockDef block = content.block(FALLBACK.getOrDefault(blockName, blockName));
                    // unknown and legacy blocks read as air, which is dropped
                    blocks[i] = block == null || block.legacy() || block.id() == 0 ? null : block;
                }
                int total = stream.readInt();
                var tiles = new ArrayList<Schematic.Tile>(total);
                for(int i = 0; i < total; i++){
                    int index = stream.readByte();
                    BlockDef block = index >= 0 && index < length ? blocks[index] : null;
                    int position = stream.readInt();
                    Object config = readObject(stream, name);
                    int rotation = stream.readByte();
                    if(block != null) tiles.add(new Schematic.Tile(block, Point2.x(position), Point2.y(position), config, rotation));
                }
                return new Schematic(name, width, height, tags, tiles);
            }
        }catch(IOException e){
            throw new UncheckedIOException(name + ": " + e.getMessage(), e);
        }
    }

    /** {@code TypeIO.readObject(read, false, null)} for the types the bundled files use. */
    private static Object readObject(DataInputStream in, String name) throws IOException{
        int type = in.readByte();
        return switch(type){
            case 0 -> null;
            case 1 -> in.readInt();
            case 2 -> in.readLong();
            case 3 -> in.readFloat();
            case 4 -> in.readByte() != 0 ? in.readUTF() : null;
            case 5 -> new Schematic.ContentValue(in.readByte(), in.readShort());
            case 6 -> {
                var ints = new int[in.readShort()];
                for(int i = 0; i < ints.length; i++) ints[i] = in.readInt();
                yield ints;
            }
            case 7 -> new Point2(in.readInt(), in.readInt());
            case 8 -> {
                var points = new Point2[in.readUnsignedByte()];
                for(int i = 0; i < points.length; i++) points[i] = Point2.unpack(in.readInt());
                yield points;
            }
            case 10 -> in.readBoolean();
            case 11 -> in.readDouble();
            case 14 -> {
                var bytes = new byte[in.readInt()];
                in.readFully(bytes);
                yield bytes;
            }
            default -> throw new IllegalArgumentException(name + ": config type " + type + " isn't supported");
        };
    }

    /** The files, read once: immutable after the class initializes. */
    private static final class Files{
        static final List<String> NAMES;
        static final Map<String, byte[]> DATA;

        static{
            String list = new String(resource("basepartnames"), StandardCharsets.UTF_8);
            NAMES = List.of(list.split("\n"));
            var data = new HashMap<String, byte[]>();
            for(String name : NAMES) data.put(name, resource("baseparts/" + name));
            DATA = Map.copyOf(data);
        }

        private static byte[] resource(String path){
            try(InputStream in = BaseParts.class.getResourceAsStream(DIR + path)){
                if(in == null) throw new IllegalStateException("the content pack has no " + DIR + path);
                return in.readAllBytes();
            }catch(IOException e){
                throw new UncheckedIOException(e);
            }
        }
    }
}
