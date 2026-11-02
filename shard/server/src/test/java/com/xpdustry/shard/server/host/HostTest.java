package com.xpdustry.shard.server.host;

import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.sim.ai.*;
import com.xpdustry.shard.sim.path.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.stream.*;

import static org.junit.jupiter.api.Assertions.*;

class HostTest{
    @Test
    void aLoadedWorldSettlesItsPathsAndTheFirstTickIsUnchanged() throws Exception{
        var loader = new MapLoader(Epoch.vanilla(), BuildingCodecs.builtIn());
        SaveFile save = MapCatalogTest.READER.read(MapCatalog.load(MapCatalogTest.jar(), null, MapCatalogTest.READER).find("debrisField").orElseThrow().read());
        Rules rules = loader.hostRules(save, Gamemode.SURVIVAL);
        WorldConfig template = HostedSystems.template(Epoch.vanilla(), 7, 256);
        var config = new WorldConfig(template.content(), save.map().width(), save.map().height(), 7, template.systems(), template.kinds(),
            template.inboxCapacity(), template.journal());

        World settled = Host.load(loader, save, config, rules).world();
        // as hosting did before: the first tick's PATH_FLUSH ran the full refresh
        MapLoader.Loaded loaded = loader.loadMap(save, config, rules);
        MapLoader.play(loaded);
        World plain = loaded.world();
        plain.part(WorldRules.class).pushPathRules(plain, loaded.state().wave);
        assertNotEquals(plain.hash(), settled.hash(), "the refresh is still queued in the plain world");

        plain.tick();
        settled.tick();
        assertEquals(plain.hash(), settled.hash());
        assertEquals(plain.part(PathService.class).rules(), settled.part(PathService.class).rules());
    }

    /**
     * {@link Host#load} builds the wave team's preloaded fields before the first tick, and the world runs as when the
     * first tick's {@code PATH_FLUSH} took them: the same hash every tick, through the first waves walking the fields,
     * and the same fields, tile by tile. On maps with naval spawns too, where the preload takes a second field.
     */
    @Test
    void theWaveFieldsAreBuiltAtLoadAndThePathsComeOutTheSame() throws Exception{
        var loader = new MapLoader(Epoch.vanilla(), BuildingCodecs.builtIn());
        MapCatalog maps = MapCatalog.load(MapCatalogTest.jar(), null, MapCatalogTest.READER);
        for(String name : List.of("archipelago", "islands", "debrisField", "maze")){
            SaveFile save = MapCatalogTest.READER.read(maps.find(name).orElseThrow().read());
            Rules rules = loader.hostRules(save, Gamemode.SURVIVAL);
            // waves within the test, so units walk the fields
            rules.initialWaveSpacing = 120f;
            rules.waveSpacing = 300f;
            WorldConfig template = HostedSystems.template(Epoch.vanilla(), 7, 256);
            var config = new WorldConfig(template.content(), save.map().width(), save.map().height(), 7, template.systems(), template.kinds(),
                template.inboxCapacity(), template.journal());

            World hosted = Host.load(loader, save, config, rules).world();
            // as hosting did before: settled, and the first tick's PATH_FLUSH took the references and built the fields
            MapLoader.Loaded loaded = loader.loadMap(save, config, rules);
            MapLoader.play(loaded);
            World plain = loaded.world();
            plain.part(WorldRules.class).pushPathRules(plain, loaded.state().wave);
            plain.part(PathService.class).settle(plain);
            PathService paths = hosted.part(PathService.class), plainPaths = plain.part(PathService.class);
            assertTrue(hosted.part(WaveFields.class).loaded, name);
            assertTrue(paths.fields() > 0, name + ": the fields were taken at load");
            for(int handle = 0; handle < paths.fields(); handle++) assertTrue(paths.ready(handle), name + ": field " + handle + " was built at load");
            assertEquals(0, plainPaths.fields(), name);

            for(int tick = 1; tick <= 900; tick++){
                plain.tick();
                hosted.tick();
                assertEquals(plain.hash(), hosted.hash(), name + ", tick " + tick);
            }
            assertTrue(hosted.units().size() > 0, name + ": no wave came");
            assertEquals(plainPaths.fields(), paths.fields(), name);
            int tiles = hosted.tiles().width() * hosted.tiles().height();
            for(int handle = 0; handle < paths.fields(); handle++){
                for(int pos = 0; pos < tiles; pos++){
                    int field = handle, tile = pos;
                    assertEquals(plainPaths.distance(field, tile), paths.distance(field, tile), () -> name + ", field " + field + ", tile " + tile);
                }
            }
        }
    }

    @Test
    void fewestFirstSortsOneReadingOfEachWorld(){
        assertEquals(List.of("c", "a", "d", "b"), Host.fewestFirst(List.of("a", "b", "c", "d"), Map.of("a", 2, "b", 5, "c", 0, "d", 2)::get),
            "fewest first, ties in their order");

        // seats that move under the sort, as other joins take them: a live comparator over 100 worlds can make TimSort throw
        var worlds = IntStream.range(0, 100).boxed().toList();
        var random = new Random(24);
        for(int round = 0; round < 1000; round++){
            List<Integer> sorted = Host.fewestFirst(worlds, world -> random.nextInt(12));
            assertEquals(new HashSet<>(worlds), new HashSet<>(sorted));
            assertEquals(worlds.size(), sorted.size());
        }
    }
}
