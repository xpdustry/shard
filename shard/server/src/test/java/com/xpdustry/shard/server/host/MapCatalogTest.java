package com.xpdustry.shard.server.host;

import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.content.epoch.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class MapCatalogTest{
    static final MsavReader READER = new MsavReader(Epoch.vanilla());

    static Path jar() throws Exception{
        String url = Objects.requireNonNull(MapCatalogTest.class.getClassLoader().getResource("maps/default/fortress.msav")).toString();
        return Path.of(java.net.URI.create(url.substring("jar:".length(), url.indexOf("!/"))));
    }

    @Test
    void theJarGivesTheEighteenDefaultMaps(@TempDir Path custom) throws Exception{
        Files.writeString(custom.resolve("notes.txt"), "not a map");
        try(var fs = FileSystems.newFileSystem(jar())){
            Files.copy(fs.getPath("maps/default/maze.msav"), custom.resolve("my_maze.msav"));
        }
        MapCatalog maps = MapCatalog.load(jar(), custom, READER);
        assertEquals(19, maps.all().size());
        assertEquals(MapCatalog.DEFAULT_MAPS, maps.all().subList(0, 18).stream().map(MapCatalog.Entry::file).toList());
        MapCatalog.Entry mudFlats = maps.find("mudFlats").orElseThrow();
        assertEquals("Mud Flats", mudFlats.name());
        assertFalse(mudFlats.custom());
        assertEquals("mud-flats", MapCatalog.defaultId(mudFlats));
        assertSame(mudFlats, maps.find("mud_flats").orElseThrow(), "by name tag, _ for a space, any case");
        assertSame(mudFlats, maps.find("MUD FLATS").orElseThrow());
        assertTrue(READER.read(mudFlats.read()).map().width() > 0);

        MapCatalog.Entry mine = maps.find("my_maze").orElseThrow();
        assertTrue(mine.custom());
        assertEquals("my-maze", MapCatalog.defaultId(mine));
        assertSame(maps.find("maze").orElseThrow(), maps.all().getFirst(), "a name tag shared with a custom map finds the default first");
        assertTrue(maps.find("nowhere").isEmpty());
    }

    @Test
    void anUnreadableCustomMapIsLeftOutNotFatal(@TempDir Path custom) throws Exception{
        // Imperium's map cache held empty .msav files; vanilla skips them (Maps.java:149-150), so Shard must still start
        Files.write(custom.resolve("empty.msav"), new byte[0]);
        Files.write(custom.resolve("truncated.msav"), new byte[]{0x78, (byte)0x9c, 0x01});
        try(var fs = FileSystems.newFileSystem(jar())){
            Files.copy(fs.getPath("maps/default/maze.msav"), custom.resolve("good.msav"));
        }
        MapCatalog maps = MapCatalog.load(jar(), custom, READER);
        assertEquals(19, maps.all().size(), "the 18 defaults and the one readable custom map");
        assertTrue(maps.find("good").isPresent());
        assertTrue(maps.find("empty").isEmpty());
        assertTrue(maps.find("truncated").isEmpty());
    }

    @Test
    void aMissingJarIsAnError(@TempDir Path dir){
        assertThrows(FileNotFoundException.class, () -> MapCatalog.load(dir.resolve("missing.jar"), null, READER));
        assertDoesNotThrow(() -> MapCatalog.load(null, dir.resolve("missing"), READER), "a missing map directory is no maps");
    }
}
