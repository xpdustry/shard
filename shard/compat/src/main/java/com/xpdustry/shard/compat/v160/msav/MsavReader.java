package com.xpdustry.shard.compat.v160.msav;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.compat.v160.blocks.environment.*;
import com.xpdustry.shard.compat.v160.msav.SaveFile.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.compat.v160.world.MapState.*;
import com.xpdustry.shard.content.*;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Reads msav files of every version vanilla v160.7 reads, 1 to 13 ({@code SaveIO} and {@code io/versions}), into a
 * {@link SaveFile}. Regions are length-delimited; each must be consumed exactly, which vanilla also checks.
 *
 * <p>Layouts by version: regions are meta, content, map, entities, plus patches (v11 after content, v12 and later
 * after meta), custom chunks (v7) and markers (v8). The map region is the legacy format in v1–3 (a building chunk
 * wherever the block has a building, read into the current layout), u16-length chunks with the one-byte data format in v4–9, and u32-length chunks
 * from v10. The entities region holds legacy entities in v1–3 (v3 adds int-config plans), then plans and entities with
 * the class mapping from v5 and entity ids from v6.
 */
public final class MsavReader{
    /** The newest version, the one vanilla v160.7 writes. */
    public static final int LATEST = 13;

    private final ContentEpoch content;

    /** Reads files for {@code content}, whose names the content header maps the file's ids to. */
    public MsavReader(ContentEpoch content){
        this.content = content;
    }

    /** Whether {@code file} starts like an msav (inflated) or a zlib stream. */
    public static boolean looksLikeMsav(byte[] file){
        return isRaw(file) || file.length >= 2 && (file[0] & 0xff) == 0x78;
    }

    /** Reads a file as vanilla writes it (zlib-deflated), or already inflated. */
    public SaveFile read(byte[] file){
        byte[] raw = isRaw(file) ? file : inflate(file);
        var in = new Reads(raw);
        if(raw.length < 8 || !isRaw(raw)) throw new MsavException("not an msav file: no MSAV header");
        in.skip(4);
        int version = in.i();
        if(version < 1 || version > LATEST){
            throw new MsavException("msav version " + version + " is not supported: vanilla v160.7 reads versions 1 to " + LATEST);
        }
        return new Parse(raw, in, version).read();
    }

    /** Only the meta region's string map, as vanilla's {@code getMeta} and {@code MapIO.readMap} read it. */
    public SequencedMap<String, String> meta(byte[] file){
        byte[] raw = isRaw(file) ? file : inflate(file);
        var in = new Reads(raw);
        in.skip(8);
        var parse = new Parse(raw, in, 0);
        return parse.region("meta", Parse::stringMap);
    }

    private static boolean isRaw(byte[] bytes){
        return bytes.length >= 4 && bytes[0] == 'M' && bytes[1] == 'S' && bytes[2] == 'A' && bytes[3] == 'V';
    }

    private static byte[] inflate(byte[] deflated){
        try(var in = new InflaterInputStream(new ByteArrayInputStream(deflated))){
            return in.readAllBytes();
        }catch(IOException e){
            throw new MsavException("not an msav file: cannot inflate it (" + e.getMessage() + ")", e);
        }
    }

    private final class Parse{
        final byte[] raw;
        final Reads in;
        final int version;
        ContentHeader.IdMapping ids;

        Parse(byte[] raw, Reads in, int version){
            this.raw = raw;
            this.in = in;
            this.version = version;
        }

        SaveFile read(){
            SequencedMap<String, String> meta = region("meta", Parse::stringMap);
            byte[] patches = version >= 12 ? region("patches", r -> r.b(r.remaining())) : null;
            ContentHeader header = region("content", ContentHeader::read);
            if(version == 11) patches = region("patches", r -> r.b(r.remaining()));
            ids = header.mapping(content);
            SaveMap map = region("map", this::map);
            Entities entities = region("entities", this::entities);
            byte[] markers = version >= 8 ? region("markers", r -> r.b(r.remaining())) : null;
            List<CustomChunk> custom = version >= 7 ? region("custom", this::customChunks) : List.of();
            return new SaveFile(version, meta, patches, header, map, entities.mapping, entities.plans, entities.entities,
                entities.legacy, markers, custom);
        }

        <T> T region(String name, java.util.function.Function<Reads, T> reader){
            int start = in.position();
            int length;
            try{
                length = in.i();
            }catch(UncheckedIOException e){
                throw new MsavException("msav v" + version + ": the file ends before region \"" + name + "\" at byte " + start, e);
            }
            if(length < 0 || length > in.remaining()){
                throw new MsavException("msav v" + version + ": region \"" + name + "\" at byte " + start + " claims " + length
                    + " bytes, but " + in.remaining() + " remain");
            }
            var region = new Reads(raw, in.position(), length);
            in.skip(length);
            T out;
            try{
                out = reader.apply(region);
            }catch(MsavException e){
                throw e;
            }catch(RuntimeException e){
                throw new MsavException("msav v" + version + ": region \"" + name + "\" (byte " + (start + 4) + "): failed at byte "
                    + region.position() + " (+" + (region.position() - start - 4) + "): " + e, e);
            }
            if(region.remaining() != 0){
                throw new MsavException("msav v" + version + ": region \"" + name + "\" (byte " + (start + 4) + ", " + length
                    + " bytes): " + region.remaining() + " bytes left unread");
            }
            return out;
        }

        static SequencedMap<String, String> stringMap(Reads in){
            var map = new LinkedHashMap<String, String>();
            int size = in.s();
            for(int i = 0; i < size; i++){
                String key = in.str();
                map.put(key, in.str());
            }
            return map;
        }

        SaveMap map(Reads in){
            int width = in.us(), height = in.us(), size = width * height;
            short[] floors = new short[size], overlays = new short[size], blocks = new short[size];
            for(int i = 0; i < size; i++){
                short floor = in.s(), overlay = in.s();
                int run = in.ub();
                if(i + run >= size) throw new MsavException("floor run at tile " + i + " covers " + (run + 1) + " tiles, past the end of the " + width + "x" + height + " map");
                for(int j = i; j <= i + run; j++){
                    floors[j] = floor;
                    overlays[j] = overlay;
                }
                i += run;
            }
            var data = new TreeMap<Integer, Long>();
            var oldData = new TreeMap<Integer, Byte>();
            var buildings = new TreeMap<Integer, BuildingChunk>();
            var parts = new BitSet(size);
            if(version <= 3){
                legacyBlocks(in, width, blocks, buildings, parts);
            }else{
                boolean shortChunks = version <= 9;
                for(int i = 0; i < size; i++){
                    short block = in.s();
                    blocks[i] = block;
                    int packed = in.b();
                    boolean hadEntity = (packed & 1) != 0, hadOld = shortChunks && (packed & 2) != 0, hadNew = (packed & 4) != 0;
                    if(hadNew) data.put(i, TileDataCodec.read(in));
                    boolean center = !hadEntity || in.bool();
                    if(hadEntity){
                        if(center){
                            int length = shortChunks ? in.us() : in.i();
                            if(length < 1) throw new MsavException("building chunk at tile " + i + " is " + length + " bytes long");
                            byte[] chunk = in.b(length);
                            buildings.put(i, new BuildingChunk(chunk[0], Arrays.copyOfRange(chunk, 1, chunk.length)));
                        }else{
                            parts.set(i);
                        }
                    }else if(hadOld || hadNew){
                        // Tiles with data are never part of a run; the one-byte format stores its byte here.
                        if(hadOld) oldData.put(i, in.b());
                    }else{
                        int run = in.ub();
                        if(i + run >= size) throw new MsavException("block run at tile " + i + " covers " + (run + 1) + " tiles, past the end of the map");
                        for(int j = i + 1; j <= i + run; j++) blocks[j] = block;
                        i += run;
                    }
                }
            }
            return new SaveMap(width, height, floors, overlays, blocks, data, oldData, buildings, parts);
        }

        /**
         * {@code LegacySaveVersion.readMap}: a u16-length chunk after every tile whose block has a building, converted
         * to the current layout ({@link #legacyChunk}). Vanilla doesn't set a tile that a building already covers to
         * that building's block or to air, nor the tiles of an air run; those tiles are {@code parts}.
         */
        void legacyBlocks(Reads in, int width, short[] blocks, TreeMap<Integer, BuildingChunk> buildings, BitSet parts){
            int size = blocks.length;
            // the centre of the building covering each tile, as vanilla's tile.build, or -1
            int[] cover = new int[size];
            Arrays.fill(cover, -1);
            for(int i = 0; i < size; i++){
                short block = in.s();
                blocks[i] = block;
                int current = Math.max(ids.toCurrent(ContentType.BLOCK, block), 0);
                BlockDef def = content.block(current);
                int owner = cover[i];
                boolean occupied = owner >= 0 && owner != i
                    && (current == 0 || Math.max(ids.toCurrent(ContentType.BLOCK, blocks[owner]), 0) == current);
                if(occupied){
                    parts.set(i);
                }else{
                    place(cover, width, i, def);
                }
                if(def.hasBuilding()){
                    int length = in.us();
                    if(length < 1) throw new MsavException("legacy building chunk at tile " + i + " is empty");
                    byte[] chunk = in.b(length);
                    // a part reads its chunk into the building covering it, over that building's own
                    buildings.put(occupied ? owner : i, legacyChunk(chunk, i));
                }else{
                    int run = in.ub();
                    if(i + run >= size) throw new MsavException("block run at tile " + i + " covers " + (run + 1) + " tiles, past the end of the map");
                    for(int j = i + 1; j <= i + run; j++){
                        blocks[j] = block;
                        // "air is a waste of time": vanilla leaves the tiles of an air run as they are
                        if(current == 0) parts.set(j);
                        else place(cover, width, j, def);
                    }
                    i += run;
                }
            }
        }

        /**
         * {@code tile.setBlock(def)} for coverage: a building replaced anywhere in its area is gone, and a block with a
         * building covers its area from {@code centre}.
         */
        static void place(int[] cover, int width, int centre, BlockDef def){
            int old = cover[centre];
            if(old >= 0){
                for(int j = 0; j < cover.length; j++) if(cover[j] == old) cover[j] = -1;
            }
            if(!def.hasBuilding()) return;
            int x = centre % width, y = centre / width, offset = -(def.size() - 1) / 2, height = cover.length / width;
            for(int dx = 0; dx < def.size(); dx++){
                for(int dy = 0; dy < def.size(); dy++){
                    int wx = x + dx + offset, wy = y + dy + offset;
                    if(wx >= 0 && wy >= 0 && wx < width && wy < height) cover[wx + wy * width] = centre;
                }
            }
        }

        /**
         * A v1–3 chunk (version, u16 health, a packed byte with the team in its high nibble, or 8 there and the team
         * next, and the rotation in its low one, then the modules and the block's body) in the current layout. Its
         * modules and the old consume byte are what {@code readBase} reads after a header without the version bit,
         * so only the header changes: f32 health, the rotation and the team. The body stays at its revision.
         */
        static BuildingChunk legacyChunk(byte[] chunk, int tile){
            var in = new Reads(chunk);
            int revision;
            float health;
            int packed, team;
            try{
                revision = in.b();
                health = in.us();
                packed = in.b();
                team = (packed >> 4 & 0xf) == 8 ? in.b() : packed >> 4 & 0xf;
            }catch(RuntimeException e){
                throw new MsavException("legacy building chunk at tile " + tile + " is too short for its header", e);
            }
            var out = new Writes(chunk.length + 2);
            out.f(health);
            out.b(packed & 0xf);
            out.b(team);
            out.b(in.b(in.remaining()));
            return new BuildingChunk(revision, out.toByteArray());
        }

        record Entities(List<EntityMappingEntry> mapping, List<TeamPlans> plans, List<EntityChunk> entities, int legacy){}

        Entities entities(Reads in){
            List<EntityMappingEntry> mapping = new ArrayList<>();
            List<TeamPlans> plans = List.of();
            List<EntityChunk> entities = new ArrayList<>();
            int legacy = 0;
            if(version <= 3){
                if(version == 3) plans = legacyPlans(in);
                legacy = legacyEntities(in);
                return new Entities(mapping, plans, entities, legacy);
            }
            if(version >= 5){
                int amount = in.s();
                for(int i = 0; i < amount; i++){
                    int id = in.s();
                    mapping.add(new EntityMappingEntry(id, in.str()));
                }
            }
            plans = plans(in);
            int amount = in.i();
            for(int i = 0; i < amount; i++){
                int length = version <= 9 ? in.us() : in.i();
                if(length < 1) throw new MsavException("entity chunk " + i + " is " + length + " bytes long");
                int start = in.position();
                int classId = in.ub();
                boolean hasId = version >= 6;
                if(hasId && length < 5) throw new MsavException("entity chunk " + i + " is too short for its id");
                int id = hasId ? in.i() : -1;
                byte[] body = in.b(length - (in.position() - start));
                entities.add(new EntityChunk(classId, id, body));
            }
            return new Entities(mapping, plans, entities, legacy);
        }

        /** {@code readTeamBlocks}: a team listed twice keeps its last plans, and only the first plan per tile counts. */
        List<TeamPlans> plans(Reads in){
            var teams = new LinkedHashMap<Integer, List<TeamPlan>>();
            int count = in.i();
            for(int i = 0; i < count; i++){
                int team = (byte)in.i() & 0xff;
                int blocks = in.i();
                var list = new ArrayList<TeamPlan>();
                var used = new TreeSet<Integer>();
                for(int j = 0; j < blocks; j++){
                    short x = in.s(), y = in.s(), rotation = in.s(), block = in.s();
                    int start = in.position();
                    TypeObjects.skip(in);
                    byte[] config = Arrays.copyOfRange(raw, start, in.position());
                    if(used.add(x << 16 | y & 0xffff)) list.add(new TeamPlan(x, y, rotation, block, config));
                }
                teams.remove(team);
                teams.put(team, List.copyOf(list));
            }
            var out = new ArrayList<TeamPlans>();
            teams.forEach((team, list) -> out.add(new TeamPlans(team, list)));
            return List.copyOf(out);
        }

        /** {@code Save3}: plans whose config is a bare int, which vanilla holds as an Integer and writes as one. */
        List<TeamPlans> legacyPlans(Reads in){
            var out = new ArrayList<TeamPlans>();
            int count = in.i();
            for(int i = 0; i < count; i++){
                int team = (byte)in.i() & 0xff;
                int blocks = in.i();
                var list = new ArrayList<TeamPlan>();
                for(int j = 0; j < blocks; j++){
                    short x = in.s(), y = in.s(), rotation = in.s(), block = in.s();
                    int config = in.i();
                    var object = new Writes(5);
                    object.b(1);
                    object.i(config);
                    list.add(new TeamPlan(x, y, rotation, block, object.toByteArray()));
                }
                out.add(new TeamPlans(team, List.copyOf(list)));
            }
            return List.copyOf(out);
        }

        /** {@code readLegacyEntities}: groups of u16-length chunks, all skipped. */
        int legacyEntities(Reads in){
            int skipped = 0;
            int groups = in.b();
            for(int i = 0; i < groups; i++){
                int amount = in.i();
                for(int j = 0; j < amount; j++){
                    int length = in.us();
                    in.b(length);
                    skipped++;
                }
            }
            return skipped;
        }

        List<CustomChunk> customChunks(Reads in){
            var out = new ArrayList<CustomChunk>();
            int amount = in.i();
            for(int i = 0; i < amount; i++){
                String name = in.str();
                int length = in.i();
                out.add(new CustomChunk(name, in.b(length)));
            }
            return List.copyOf(out);
        }
    }
}
