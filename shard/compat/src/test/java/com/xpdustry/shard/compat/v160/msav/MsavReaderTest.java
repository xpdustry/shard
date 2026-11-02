package com.xpdustry.shard.compat.v160.msav;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.compat.v160.fixtures.*;
import com.xpdustry.shard.testkit.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.util.*;
import java.util.zip.*;

import static org.junit.jupiter.api.Assertions.*;

class MsavReaderTest{
    static final MsavReader READER = new MsavReader(Worlds.EPOCH);

    @Test
    void readsEveryBundledMap(){
        var versions = new TreeMap<Integer, Integer>();
        List<String> maps = BundledMaps.all();
        for(String path : maps){
            SaveFile save = READER.read(BundledMaps.read(path));
            versions.merge(save.version(), 1, Integer::sum);
            assertEquals(Integer.parseInt(save.meta().get("width")), save.map().width(), path);
            assertEquals(Integer.parseInt(save.meta().get("height")), save.map().height(), path);
            assertEquals(save.map().width() * save.map().height(), save.map().floors().length, path);
            assertNotNull(save.header(), path);
        }
        assertEquals(114, maps.size());
        assertEquals(Set.of(2, 4, 5, 7, 8, 11, 13), versions.keySet(), versions.toString());
    }

    @Test
    void defaultMapsAreVersionsFourToSeven(){
        var expected = Map.of("archipelago", 5, "domain", 7, "glacier", 7, "passage", 7, "veins", 7);
        for(String name : BundledMaps.DEFAULT){
            SaveFile save = READER.read(BundledMaps.read("maps/default/" + name + ".msav"));
            assertEquals(expected.getOrDefault(name, 4), save.version(), name);
            assertFalse(save.hasPatches(), name);
            assertFalse(save.map().buildings().isEmpty(), name);
        }
    }

    @Test
    void readsInflatedFiles(){
        byte[] file = BundledMaps.read("maps/default/fortress.msav");
        byte[] raw = inflate(file);
        assertTrue(MsavReader.looksLikeMsav(raw));
        assertTrue(MsavReader.looksLikeMsav(file));
        assertEquals(READER.read(file).meta(), READER.read(raw).meta());
    }

    @Test
    void rejectsUnsupportedVersions(){
        for(int version : new int[]{0, 14, -1}){
            var out = new Writes();
            out.b(new byte[]{'M', 'S', 'A', 'V'});
            out.i(version);
            out.i(0);
            var error = assertThrows(MsavException.class, () -> READER.read(out.toByteArray()));
            assertTrue(error.getMessage().contains("version " + version + " is not supported"), error.getMessage());
        }
    }

    @Test
    void rejectsOtherFiles(){
        assertThrows(MsavException.class, () -> READER.read("not a map".getBytes()));
        assertThrows(MsavException.class, () -> READER.read(new byte[]{0x78, 0x01, 2, 3}));
    }

    @Test
    void rejectsTruncatedRegions(){
        byte[] raw = inflate(BundledMaps.read("maps/default/fortress.msav"));
        var error = assertThrows(MsavException.class, () -> READER.read(Arrays.copyOf(raw, raw.length / 2)));
        assertTrue(error.getMessage().contains("region"), error.getMessage());
    }

    static byte[] inflate(byte[] deflated){
        try(var in = new InflaterInputStream(new ByteArrayInputStream(deflated))){
            return in.readAllBytes();
        }catch(IOException e){
            throw new UncheckedIOException(e);
        }
    }
}
