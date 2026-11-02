package com.xpdustry.shard.compat.v160;

import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.content.patch.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.testkit.BundledMaps;
import com.xpdustry.shard.sim.rules.VanillaIds;
import mindustry.io.*;
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
 * Gate (W5.4): the bundled maps with data patches, {@code hidden/242} and {@code littoralShipyard}, carry them as
 * vanilla does. Shard hosts each ({@link MapLoader#loadMap}); vanilla loads the same file as {@code World.loadMap}
 * does. The world stream's data patch section and the v13 save's patch region are byte-equal to vanilla's, and equal
 * to the file's own region; a vanilla client reading Shard's stream loads the same patches. The rest of the stream and
 * the save are held to vanilla by the L3 gates ({@code WorldStreamOracleTest}, {@code GenerationFiltersOracleTest},
 * {@code BuildingBaseOracleTest}), which take these maps now; {@code PatchedMapsOracleTest} plays them.
 */
class MapPatchesOracleTest{
    static List<String> maps(){
        return List.of("maps/serpulo/hidden/242.msav", "maps/serpulo/littoralShipyard.msav");
    }

    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("maps")
    void streamAndSaveCarryTheMapsPatchesAsVanillas(String name){
        Hosted shard = shardHost(name);
        var state = shard.loaded().state();
        assertFalse(state.assets.patches().isEmpty(), "the map's patches are the world's");
        assertSame(com.xpdustry.shard.content.epoch.Epoch.vanilla(), shard.loaded().world().content(),
            "the bundled maps' patches change nothing, so the world plays vanilla's content");

        byte[] vanillaStream = vanillaLoaded(name);
        int patches = state_data_patches();
        var vanillaSave = new ByteArrayOutputStream();
        SaveIO.write(vanillaSave, new SaveOptions());
        byte[] shardSave = MsavWriter.write(MsavWriter.meta(shard.source(), state.tags, VanillaIds.SHARDED, false, 0L), shard.source());
        byte[] file = patchRegion(BundledMaps.read(name));

        String expected = hex(section(vanillaStream));
        assertEquals(expected, hex(section(shard.raw())), name + ": the world stream's data patch section");
        assertEquals(expected, hex(file), name + ": vanilla writes the file's region back as it read it");
        assertEquals(hex(patchRegion(vanillaSave.toByteArray())), hex(patchRegion(shardSave)), name + ": the save's patch region");

        // a vanilla client loads the patches Shard streams
        Oracle.WorldView view = Oracle.decodeWorldStream(shard.raw());
        assertEquals(List.of(), view.warnings(), name);
        assertEquals(patches, state_data_patches(), name + ": the client holds the map's patches");
        assertEquals(state.assets.patches().size(), patches);
        Vanilla.reset();
        assertEquals(0, state_data_patches(), "logic.reset unloads them");
    }

    /** {@code state.data.getPatches().size}. */
    static int state_data_patches(){
        return mindustry.Vars.state.data.getPatches().size;
    }

    /** The data patch section at the start of a raw world stream, as long as vanilla's reader takes it. */
    static byte[] section(byte[] raw){
        try{
            var in = new DataInputStream(new ByteArrayInputStream(raw));
            return Arrays.copyOf(raw, sectionLength(in));
        }catch(IOException e){
            throw new UncheckedIOException(e);
        }
    }

    /** The length of a v13 section ({@code writeDataPatches}), read as vanilla reads it. */
    static int sectionLength(DataInputStream in) throws IOException{
        int length = 8;
        in.readInt();
        int count = in.readInt();
        for(int i = 0; i < count; i++){
            int type = in.readByte();
            String path = in.readUTF();
            boolean embedded = in.readBoolean();
            length += 1 + 2 + path.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1;
            if(!embedded){
                in.skipNBytes(32);
                length += 32;
                continue;
            }
            if(type == 1){
                in.readShort();
                length += 2;
            }
            int size = in.readInt();
            in.skipNBytes(size);
            length += 4 + size;
        }
        return length;
    }

    /** An msav file's patch region (v12 and later: right after the meta region). */
    static byte[] patchRegion(byte[] file){
        byte[] raw = file[0] == 'M' ? file : inflate(file);
        var in = new DataInputStream(new ByteArrayInputStream(raw));
        try{
            in.skipNBytes(4);
            int version = in.readInt();
            assertTrue(version >= 12, "version " + version);
            in.skipNBytes(in.readInt());
            return in.readNBytes(in.readInt());
        }catch(IOException e){
            throw new UncheckedIOException(e);
        }
    }

    static byte[] inflate(byte[] deflated){
        try(var in = new InflaterInputStream(new ByteArrayInputStream(deflated))){
            return in.readAllBytes();
        }catch(IOException e){
            throw new UncheckedIOException(e);
        }
    }

    static String hex(byte[] bytes){
        return HexFormat.of().formatHex(bytes);
    }
}
