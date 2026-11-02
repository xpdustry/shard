package com.xpdustry.shard.compat.v160;

import com.xpdustry.shard.compat.v160.fixtures.*;
import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.ContentType;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.testkit.BundledMaps;
import arc.util.io.*;
import mindustry.ctype.*;
import mindustry.entities.*;
import mindustry.io.*;
import mindustry.world.Tile;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;

import java.io.*;
import java.util.*;
import java.util.zip.*;

import static com.xpdustry.shard.compat.v160.OracleWorlds.*;
import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Every bundled map loaded from its file by Shard and by vanilla: the same buildings at the same tiles, each one's
 * {@link BuildingBase} byte-identical to vanilla's {@code writeBase}, a ported kind's whole chunk identical to
 * vanilla's {@code version()} and {@code writeAll}, and a passthrough building's re-emitted chunk read whole by
 * vanilla's {@code readAll}, back to the state vanilla loaded. Legacy blocks are gone from both, and the next entity id
 * is vanilla's: turrets' block units and power graphs drew theirs while the buildings were made.
 */
class BuildingBaseOracleTest{
    /**
     * Maps Shard refuses, with what the refusal says. None since W5.4: hidden/242 and littoralShipyard load with their
     * data patches ({@code MapPatchesOracleTest}).
     */
    static final Map<String, String> REFUSED = Map.of();
    /**
     * Maps with a payload whose read's draws the loader can't tell ({@link MapState#unknownDraws}): their entity ids
     * drift from vanilla's after it, so only their bytes are compared. hidden/16, 30 and 191 left with the old payload
     * unit layouts {@code :wire} reads now.
     */
    static final Set<String> UNCOUNTED_DRAWS = Set.of();

    /** Per map: legacy block origins in the file, then buildings with a {@code lastDisabler}, then with {@code visibleFlags}. */
    static final SortedMap<String, int[]> CENSUS = new TreeMap<>();

    static List<String> maps(){
        return BundledMaps.all();
    }

    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    @AfterAll
    static void report(){
        CENSUS.forEach((map, counts) -> {
            if(counts[0] + counts[1] + counts[2] > 0){
                System.out.println("census " + map + ": legacy=" + counts[0] + " lastDisabler=" + counts[1] + " visibleFlags=" + counts[2]);
            }
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("maps")
    void everyBuildingRoundTrips(String path) throws IOException{
        byte[] file = BundledMaps.read(path);
        SaveFile save = READER.read(file);
        String refusal = REFUSED.get(path);
        if(refusal != null){
            var error = assertThrows(MsavException.class, () -> LOADER.load(save, Worlds.config(0), LOADER.saveRules(save)), path);
            assertTrue(error.getMessage().contains(refusal), error.getMessage());
            return;
        }
        MapLoader.Loaded loaded = LOADER.load(save, Worlds.config(0), LOADER.saveRules(save));
        World shard = loaded.world();
        ContentEpoch content = shard.content();

        Vanilla.reset();
        clearPools();
        SaveIO.load(new InflaterInputStream(new ByteArrayInputStream(file)), world.context);
        Vanilla.runPosted();
        // before any readAll below, which may make entities
        int nextId = EntityGroup.nextId();

        var out = new ArrayList<String>();
        String unknown = loaded.state().unknownDraws;
        check(out, "draws the loader can't tell", UNCOUNTED_DRAWS.contains(path), unknown != null);
        boolean idsAgree = unknown == null;
        if(idsAgree) check(out, "next entity id", nextId, shard.ids().peek());
        TileGrid tiles = shard.tiles();
        check(out, "size", world.width() + "x" + world.height(), tiles.width() + "x" + tiles.height());
        if(!out.isEmpty() && out.getLast().startsWith("size")) fail(path + ": " + out);

        ContentHeader.IdMapping current = ContentHeader.of(content).mapping(content);
        ContentHeader.IdMapping source = save.header().mapping(content);
        int[] census = new int[3];
        var passthrough = new ArrayList<Integer>();
        String previous = "the start";
        int count = 0;
        for(int i = 0; i < tiles.size(); i++){
            if(!save.map().parts().get(i) && content.block(Math.max(source.toCurrent(ContentType.BLOCK, save.map().blocks()[i]), 0)).legacy()) census[0]++;
            Tile tile = world.tiles.geti(i);
            mindustry.gen.Building expected = tile.build != null && tile.isCenter() ? tile.build : null;
            Building at = shard.buildings().at(i);
            Building actual = at != null && at.tile() == i ? at : null;
            if((expected == null) != (actual == null)){
                out.add("(" + tile.x + ", " + tile.y + "): " + (expected == null ? "no building" : expected.block.name)
                    + " vs " + (actual == null ? "no building" : actual.block().name()));
                continue;
            }
            if(expected == null) continue;
            count++;
            String where = expected.block.name + " at (" + tile.x + ", " + tile.y + ")";
            if(expected.block.id != actual.block().id()){
                out.add(where + ": block " + actual.block().name());
                continue;
            }
            // the first building whose id differs: the one before it drew a different number of ids
            if(idsAgree && expected.id != actual.id()){
                out.add(where + ": id " + expected.id + " vs " + actual.id() + ", after " + previous);
                idsAgree = false;
            }
            previous = where;
            if(actual.lastDisabler != null) census[1]++;
            if(actual.visibleFlags != 0) census[2]++;

            check(out, where + " base", hex(vanilla(expected::writeBase)), hex(shard(w -> BuildingBase.write(actual, w, current))));
            if(actual instanceof PassthroughBuilding){
                passthrough.add(i);
            }else{
                check(out, where + " chunk", hex(written(expected)), hex(CODECS.chunk(actual, content, current)));
            }
        }

        // vanilla reads the chunk Shard re-emits for a passthrough building (its bytes keep the file's ids) whole, to the
        // state the file's own chunk reads to; fresh buildings, since some reads change the world (Conveyor.java:432)
        MappableContent[][] mapper = mapper(save.header());
        for(int i : passthrough){
            Tile tile = world.tiles.geti(i);
            String where = tile.block().name + " at (" + tile.x + ", " + tile.y + ")";
            SaveFile.BuildingChunk original = save.map().buildings().get(i);
            byte[] chunk = CODECS.chunk(shard.buildings().at(i), content, source);
            mindustry.Vars.content.setTemporaryMapper(mapper);
            try{
                var fromFile = fresh(tile);
                fromFile.readAll(new Reads(new DataInputStream(new ByteArrayInputStream(original.body()))), (byte)original.revision());
                var fromShard = fresh(tile);
                var bytes = new ByteArrayInputStream(chunk, 1, chunk.length - 1);
                fromShard.readAll(new Reads(new DataInputStream(bytes)), chunk[0]);
                check(out, where + " unread chunk bytes", 0, bytes.available());
                // the base is checked against the loaded building above: endLoad may change it (enabled, for one)
                check(out, where + " body from Shard's chunk", hex(body(fromFile)), hex(body(fromShard)));
            }catch(Throwable e){
                out.add(where + ": vanilla's readAll fails: " + e);
            }finally{
                mindustry.Vars.content.setTemporaryMapper(null);
            }
        }
        CENSUS.put(path, census);
        assertTrue(count > 0 || save.map().buildings().isEmpty(), path + ": no buildings compared");
        if(out.size() > 10) out.subList(10, out.size()).clear();
        assertEquals(List.of(), out, path);
    }

    /**
     * Empties Arc's object pools, which keep the fires, puddles and bullets earlier maps freed: an entity read from a
     * pool keeps the old object and draws no id, where a fresh server, as Shard's new world, constructs it.
     */
    @SuppressWarnings("unchecked")
    static void clearPools(){
        try{
            var field = arc.util.pooling.Pools.class.getDeclaredField("typePools");
            field.setAccessible(true);
            for(var pool : ((arc.struct.ObjectMap<Class<?>, arc.util.pooling.Pool<?>>)field.get(null)).values()) pool.clear();
        }catch(ReflectiveOperationException e){
            throw new IllegalStateException(e);
        }
    }

    /** A building of the tile's block as {@code readMap} makes one, but outside the world: not on the tile, not added. */
    static mindustry.gen.Building fresh(Tile tile){
        mindustry.gen.Building building = tile.block().newBuilding().init(tile, tile.team(), false, tile.build.rotation);
        building.enabled = true;
        return building;
    }

    /** What {@code writeMap} puts in a building's chunk: the revision, then {@code writeAll}. */
    static byte[] written(mindustry.gen.Building building){
        return vanilla(w -> {
            w.b(building.version());
            building.writeAll(w);
        });
    }

    /** The revision and the body after the base. */
    static byte[] body(mindustry.gen.Building building){
        return vanilla(w -> {
            w.b(building.version());
            building.write(w);
        });
    }

    /** Vanilla's {@code readContentHeader} mapper for {@code header}: listed types by name (blocks through the fallbacks). */
    static MappableContent[][] mapper(ContentHeader header){
        var map = new MappableContent[mindustry.ctype.ContentType.all.length][0];
        for(mindustry.ctype.ContentType type : mindustry.ctype.ContentType.all){
            if(type.ordinal() >= ContentHeader.TYPES) continue;
            List<String> names = header.names(type.ordinal());
            if(names.isEmpty()) continue;
            map[type.ordinal()] = new MappableContent[names.size()];
            for(int i = 0; i < names.size(); i++){
                String name = names.get(i);
                map[type.ordinal()][i] = mindustry.Vars.content.getByName(type, type == mindustry.ctype.ContentType.block ? SaveFileReader.fallback.get(name, name) : name);
            }
        }
        return map;
    }

    interface VanillaWriter{
        void write(Writes out) throws IOException;
    }

    static byte[] vanilla(VanillaWriter writer){
        var bytes = new ByteArrayOutputStream();
        try{
            writer.write(new Writes(new DataOutputStream(bytes)));
        }catch(IOException e){
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    static byte[] shard(java.util.function.Consumer<com.xpdustry.shard.common.io.Writes> writer){
        var out = new com.xpdustry.shard.common.io.Writes();
        writer.accept(out);
        return out.toByteArray();
    }

    static String hex(byte[] bytes){
        return HexFormat.of().formatHex(bytes);
    }
}
