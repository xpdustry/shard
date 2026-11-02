package com.xpdustry.shard.compat.v160.msav;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.compat.v160.fixtures.*;
import com.xpdustry.shard.compat.v160.rules.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.content.load.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.testkit.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class MapLoaderTest{
    static final BuildingCodecs CODECS = BuildingCodecs.builtIn();
    static final MsavReader READER = new MsavReader(Worlds.EPOCH);
    static final MapLoader LOADER = new MapLoader(Worlds.EPOCH, CODECS);
    static final WorldProjection PROJECTION = new WorldProjection(Worlds.EPOCH, CODECS);
    static final RulesJson JSON = new RulesJson(Worlds.EPOCH);

    static MapLoader.Loaded host(byte[] file){
        SaveFile save = READER.read(file);
        return LOADER.loadMap(save, Worlds.config(0), LOADER.hostRules(save, Gamemode.SURVIVAL));
    }

    @Test
    void loadsEveryDefaultMapOntoTheGrid(){
        for(String name : BundledMaps.DEFAULT){
            SaveFile save = READER.read(BundledMaps.read(name));
            MapLoader.Loaded loaded = LOADER.load(save, Worlds.config(0), LOADER.hostRules(save, Gamemode.SURVIVAL));
            World world = loaded.world();
            assertEquals(save.map().width(), world.tiles().width(), name);
            assertFalse(world.buildings().loading(), name);
            var positions = new ArrayList<Integer>();
            world.buildings().each(building -> {
                positions.add(building.tile());
                SaveFile.BuildingChunk chunk = save.map().buildings().get(building.tile());
                if(building instanceof PassthroughBuilding passthrough){
                    assertEquals(chunk.revision() & 0xff, passthrough.revision(), name);
                    // the body is what the chunk holds after the base
                    byte[] body = passthrough.body(Worlds.EPOCH), all = chunk.body();
                    assertArrayEquals(Arrays.copyOfRange(all, all.length - body.length, all.length), body, name);
                }else{
                    assertNotNull(CODECS.get(world.kinds().key(building.block())), name);
                }
                assertEquals(building.team, world.tiles().team(building.tile()), name);
            });
            assertEquals(List.copyOf(save.map().buildings().keySet()), positions, name);
            TileGrid tiles = world.tiles();
            save.map().data().forEach((pos, packed) -> {
                // darkness may rewrite data; the other bytes are the file's
                assertEquals((int)(long)packed, tiles.extraData(pos), name);
                assertEquals((byte)(packed >>> 40), tiles.floorData(pos), name);
                assertEquals((byte)(packed >>> 48), tiles.overlayData(pos), name);
            });
            // building ids are drawn in tile order, before the entities region
            assertEquals(save.entities().size(), loaded.state().entities.size(), name);
        }
    }

    @Test
    void writesAWorldStreamForEveryDefaultMap(){
        for(String name : BundledMaps.DEFAULT){
            MapLoader.Loaded loaded = host(BundledMaps.read(name));
            WorldStreamSource source = PROJECTION.capture(loaded.world(), loaded.state(), PlayerState.fresh(loaded.world().ids().next(), "shard", VanillaIds.SHARDED));
            byte[] raw = WorldStreamWriter.write(source);
            var in = new Reads(raw);
            // vanilla content: patchgen's patch is empty, so no assets
            assertEquals(WorldStreamWriter.PATCH_FORMAT_VERSION, in.i(), name);
            assertEquals(0, in.i(), name);
            assertEquals(source.rules(), in.str(), name);
            assertTrue(WorldStreamWriter.deflate(raw).length < raw.length, name);
        }
    }

    @Test
    void v13SavesReadBackToTheSameWorld(){
        for(String name : BundledMaps.DEFAULT){
            MapLoader.Loaded first = host(BundledMaps.read(name));
            Rules rules = first.world().part(WorldRules.class).rules;
            WorldStreamSource source = PROJECTION.capture(first.world(), first.state(), PlayerState.fresh(0, "shard", VanillaIds.SHARDED));
            byte[] file = MsavWriter.write(MsavWriter.meta(source, first.state().tags, rules.defaultTeam, MsavWriter.noCores(first.world(), rules.defaultTeam), 0L), source);

            SaveFile save = READER.read(file);
            assertEquals(13, save.version(), name);
            assertEquals(source.header(), save.header(), name);
            assertEquals(source.rules(), save.meta().get("rules"), name);
            assertEquals("false", save.meta().get("nocores"), name);
            MapLoader.Loaded second = LOADER.load(save, Worlds.config(0), LOADER.saveRules(save));
            assertSameTiles(first.world(), second.world(), name);
            WorldStreamSource again = PROJECTION.capture(second.world(), second.state(), PlayerState.fresh(0, "shard", VanillaIds.SHARDED));
            // default waves are built in code with a null effect, which reads back as "none", as in vanilla
            assertEquals(JSON.write(JSON.read(source.rules())), again.rules(), name);
            assertArrayEquals(mapBytes(source), mapBytes(again), name);
            assertEquals(source.entities().size(), again.entities().size(), name);
        }
    }

    @Test
    void patchedEpochsWriteTheirPatchAndReadItBack(){
        Epoch patched = Epoch.load(Pack.vanilla().with(List.of(new Pack.Source("world.yml", """
            - type: block
              id: duo
              overlay: true
              reload: 15
            """))));
        var reader = new MsavReader(patched);
        var loader = new MapLoader(patched, CODECS);
        var projection = new WorldProjection(patched, CODECS);
        WorldConfig template = WorldConfig.of(patched, 1, 1, 0).withKinds(KindRegistry.builtIn().withPassthrough());
        SaveFile map = reader.read(BundledMaps.read("fortress"));
        MapLoader.Loaded first = loader.load(map, template, loader.hostRules(map, Gamemode.SURVIVAL));
        WorldStreamSource source = projection.capture(first.world(), first.state(), PlayerState.fresh(0, "shard", VanillaIds.SHARDED));
        var patches = new Reads(source.patches());
        assertEquals(WorldStreamWriter.PATCH_FORMAT_VERSION, patches.i());
        assertEquals(1, patches.i(), "one patch asset");
        byte[] file = MsavWriter.write(MsavWriter.meta(source, first.state().tags, VanillaIds.SHARDED, false, 0L), source);

        SaveFile save = reader.read(file);
        assertTrue(save.hasPatches());
        MapLoader.Loaded second = loader.load(save, template, loader.saveRules(save));
        assertSameTiles(first.world(), second.world(), "fortress");
        // a loader of vanilla's content applies it as vanilla's patcher does (W5.4): the world plays its own patched content
        MapLoader.Loaded asVanilla = LOADER.load(save, Worlds.config(0), new Rules());
        assertTrue(((Epoch)asVanilla.world().content()).isPatched());
        assertEquals(15f, asVanilla.world().content().block("duo").fields().f("reload"));
        assertEquals(1, asVanilla.state().assets.patches().size());
        assertThrows(IllegalArgumentException.class, () -> loader.load(save, Worlds.config(0), new Rules()), "a template of other content");
    }

    @Test
    void loadsLegacyBuildings(){
        // canyon is a bundled v2 map (not a default one): its buildings are in the v1-3 layout
        SaveFile canyon = READER.read(BundledMaps.read("canyon"));
        assertEquals(2, canyon.version());
        World world = LOADER.load(canyon, Worlds.config(0), new Rules()).world();
        Building core = world.buildings().at(124, 35);
        assertNotNull(core);
        assertEquals("core-nucleus", core.block().name());
        // LegacySaveVersion.readMap keeps the saved health, without readBase's cap
        assertEquals(new Reads(canyon.map().buildings().get(core.tile()).body()).f(), core.health);
        assertEquals(canyon.map().buildings().size(), count(world));
    }

    @Test
    void noCoresIsWhetherTheTeamHasACore(){
        World world = host(BundledMaps.read("maze")).world();
        assertFalse(MsavWriter.noCores(world, VanillaIds.SHARDED));
        assertTrue(MsavWriter.noCores(world, 100));
    }

    static int count(World world){
        int[] n = {0};
        world.buildings().each(building -> n[0]++);
        return n[0];
    }

    static byte[] mapBytes(WorldStreamSource source){
        var out = new Writes();
        source.map().write(out);
        return out.toByteArray();
    }

    static void assertSameTiles(World expected, World actual, String name){
        TileGrid a = expected.tiles(), b = actual.tiles();
        assertEquals(a.size(), b.size(), name);
        for(int i = 0; i < a.size(); i++){
            assertEquals(a.image(i), b.image(i), name);
            Building x = expected.buildings().at(i), y = actual.buildings().at(i);
            assertEquals(x == null, y == null, name);
            if(x != null){
                assertEquals(x.tile(), y.tile(), name);
                assertEquals(x.health, y.health, name);
                assertEquals(x.enabled, y.enabled, name);
                assertEquals(x.efficiency, y.efficiency, name);
                assertEquals(x.getClass(), y.getClass(), name);
                if(x instanceof PassthroughBuilding p){
                    assertArrayEquals(p.body(expected.content()), ((PassthroughBuilding)y).body(actual.content()), name);
                }else{
                    // a ported kind's body in current ids
                    var ids = ContentHeader.of(expected.content()).mapping(expected.content());
                    assertArrayEquals(CODECS.chunk(x, expected.content(), ids), CODECS.chunk(y, actual.content(), ids), name);
                }
            }
        }
    }
}
