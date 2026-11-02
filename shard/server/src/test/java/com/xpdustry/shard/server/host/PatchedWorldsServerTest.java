package com.xpdustry.shard.server.host;

import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.content.patch.*;
import com.xpdustry.shard.server.*;
import com.xpdustry.shard.server.config.*;
import com.xpdustry.shard.server.world.*;
import com.xpdustry.shard.wire.v160_7.Packets;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.io.*;
import java.nio.file.*;
import java.util.zip.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * W5.4: one server hosts a map with a data patch next to one without, and neither world sees the other's content.
 * The patched map is maze with {@code block.conveyor.speed} patched, written by Shard as a custom map.
 *
 * <p>W6.2: a map with an embedded sprite runs the join's asset handshake as vanilla's {@code NetServer} does, in order
 * and bounded; a patched world hibernates and wakes from its checkpoint with its own content.
 */
class PatchedWorldsServerTest{
    static final String PATCH = "{\"name\": \"fast belts\", \"block.conveyor.speed\": 0.08}";

    @TempDir
    Path temp;

    @Test
    void aPatchedWorldAndAPlainOneKeepTheirOwnContent() throws Exception{
        Path maps = temp.resolve("maps");
        Files.createDirectories(maps);
        Files.write(maps.resolve("fastbelts.msav"), patchedMaze());

        Server server = Server.start(new Server.Options(ServerTest.configure(temp, """
            host:
              worlds: [maze, fastbelts]
            """), Map.of(), false));
        try{
            HostedWorld maze = server.host().world("maze").orElseThrow(), fast = server.host().world("fastbelts").orElseThrow();
            float mazeSpeed = maze.inspect(w -> w.content().block("conveyor").fields().f("speed")).get(Hang.MILLIS, TimeUnit.MILLISECONDS);
            float fastSpeed = fast.inspect(w -> w.content().block("conveyor").fields().f("speed")).get(Hang.MILLIS, TimeUnit.MILLISECONDS);
            String mazeContent = maze.inspect(w -> w.content().hash()).get(Hang.MILLIS, TimeUnit.MILLISECONDS);
            String fastContent = fast.inspect(w -> w.content().hash()).get(Hang.MILLIS, TimeUnit.MILLISECONDS);
            assertEquals(0.035f, mazeSpeed, "the plain world keeps vanilla's conveyor");
            assertEquals(0.08f, fastSpeed, "the patched world has its own");
            assertEquals(Epoch.vanilla().hash(), mazeContent);
            assertNotEquals(mazeContent, fastContent);
            assertEquals(0.035f, Epoch.vanilla().block("conveyor").fields().f("speed"), "the shared vanilla epoch is untouched");
        }finally{
            server.close();
        }
    }

    /** A 1x1 PNG. */
    static final byte[] SPRITE = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg==");
    static final DataAsset.ExternalAsset MARK = DataAsset.ExternalAsset.embedded(DataAsset.Type.IMAGE, "shard-mark.png", SPRITE);

    /**
     * NetServer.java:330, 955-993: the requirement stream first; {@code requestWorld} before {@code requestAssets} is
     * ignored; {@code requestAssets} gets one asset stream, its repeat nothing; {@code requestWorld} then the world. An
     * empty request gets the world at once; a request of 16,381 ids (a full packet), repeats and ids out of range, gets the one asset
     * once. A save of the world (the console's {@code save}) embeds the sprite and loads back.
     */
    @Test
    void aJoinTakesTheAssetHandshakeInVanillasOrder() throws Exception{
        Path maps = temp.resolve("maps");
        Files.createDirectories(maps);
        Files.write(maps.resolve("sprites.msav"), patchedMaze(MARK));
        Server server = Server.start(new Server.Options(ServerTest.configure(temp, """
            host:
              worlds: [sprites]
            """), Map.of(), false));
        try{
            var assets = new DataAssets(List.of(MARK));
            byte[] requirements = AssetStreams.requirements(assets), sprite = AssetStreams.assets(List.of(MARK));
            try(var alpha = new TestClient(server.transport().address())){
                alpha.connect("alpha");
                TestClient.Streamed first = alpha.stream();
                assertEquals(AssetStreams.REQUIREMENT_STREAM, first.type(), "the requirements before the world");
                assertArrayEquals(requirements, first.data(), "NetworkIO.writeRequiredAssets, deflated");
                assertEquals(32, new InflaterInputStream(new ByteArrayInputStream(first.data())).readAllBytes().length - 4, "one hash");
                // out of order: ignored, so the handshake below still runs whole
                alpha.requestWorld();
                alpha.requestAssets((short)0);
                TestClient.Streamed second = alpha.stream();
                assertEquals(AssetStreams.ASSET_STREAM, second.type(), "an early requestWorld sent no world");
                assertArrayEquals(sprite, second.data(), "NetworkIO.writeAssets: the sprite");
                alpha.requestAssets((short)0);
                alpha.barrier();
                alpha.requestWorld();
                TestClient.Streamed world = alpha.stream();
                assertEquals(Packets.WORLD_STREAM, world.type(), "a repeated requestAssets sent nothing: the world comes next");
                alpha.confirm();
                alpha.requestWorld();
                alpha.requestAssets((short)0);
                alpha.barrier();
                assertTrue(alpha.queue(Packets.STREAM_BEGIN).isEmpty(), "nothing more once in the world");
            }
            try(var beta = new TestClient(server.transport().address())){
                beta.connect("beta");
                assertEquals(AssetStreams.REQUIREMENT_STREAM, beta.stream().type());
                beta.requestAssets();
                assertEquals(Packets.WORLD_STREAM, beta.stream().type(), "all cached: the world at once");
            }
            try(var gamma = new TestClient(server.transport().address())){
                gamma.connect("gamma");
                assertEquals(AssetStreams.REQUIREMENT_STREAM, gamma.stream().type());
                // the most a packet holds: a frame's body is at most 32,768 B, 4 of them beside the payload, which is the count
                // then 2 B an id
                short[] ids = new short[16_381];
                for(int i = 0; i < ids.length; i++) ids[i] = (short)(i % 3 == 0 ? 0 : i % 3 == 1 ? -1 : 9);
                gamma.requestAssets(ids);
                TestClient.Streamed answer = gamma.stream();
                assertEquals(AssetStreams.ASSET_STREAM, answer.type());
                assertArrayEquals(sprite, answer.data(), "the one asset, once, for 16,381 ids");
                gamma.requestWorld();
                assertEquals(Packets.WORLD_STREAM, gamma.stream().type());
            }

            // the console's save (W6.4) embeds the sprite, so the save loads back here with its assets and content
            byte[] saved = server.host().world("sprites").orElseThrow().save(0L).get(Hang.MILLIS, TimeUnit.MILLISECONDS);
            SaveFile save = MapCatalogTest.READER.read(saved);
            MapLoader.FilePatches back = new MapLoader(Epoch.vanilla(), BuildingCodecs.builtIn()).filePatches(save);
            assertNotNull(back, "the save carries the map's patch and sprite");
            assertArrayEquals(SPRITE, back.assets().externals().getFirst().data(), "the sprite's bytes, embedded");
            assertEquals(0.08f, back.content().block("conveyor").fields().f("speed"), "and the patched content comes back");
        }finally{
            server.close();
        }
    }

    /**
     * A patched world's checkpoints name its own content: it hibernates once empty and a join wakes it from its
     * checkpoint ({@code ManagedWorld.restore} with the world's config), with its patch still in place.
     */
    @Test
    void aPatchedWorldHibernatesAndWakesWithItsOwnContent() throws Exception{
        Path maps = temp.resolve("maps");
        Files.createDirectories(maps);
        Files.write(maps.resolve("fastbelts.msav"), patchedMaze());
        Server server = Server.start(new Server.Options(ServerTest.configure(temp, """
            host:
              worlds: [fastbelts]
            """), Map.of(ConfigKeys.PERSISTENCE_HIBERNATE_AFTER.env(), "1s"), false));
        try{
            HostedWorld fast = server.host().world("fastbelts").orElseThrow();
            String hash = fast.inspect(w -> w.content().hash()).get(Hang.MILLIS, TimeUnit.MILLISECONDS);
            assertNotEquals(Epoch.vanilla().hash(), hash);
            assertEquals(hash, fast.managed().config().content().hash(), "the world's config has its own content");
            long deadline = System.nanoTime() + Hang.TIMEOUT.toNanos();
            while(fast.managed().lifecycle() != Lifecycle.HIBERNATED){
                if(System.nanoTime() > deadline) fail("never hibernated: " + fast.managed().lifecycle() + ", " + fast.managed().status().error());
                Thread.sleep(20);
            }
            try(var alpha = new TestClient(server.transport().address())){
                alpha.connect("alpha");
                alpha.loadWorld();
                alpha.confirm();
                assertEquals(1, fast.managed().restores(), "woken from its checkpoint");
                assertEquals(hash, fast.inspect(w -> w.content().hash()).get(Hang.MILLIS, TimeUnit.MILLISECONDS));
                assertEquals(0.08f, fast.inspect(w -> w.content().block("conveyor").fields().f("speed")).get(Hang.MILLIS, TimeUnit.MILLISECONDS));
            }
        }finally{
            server.close();
        }
    }

    /** Maze as a v13 map whose patch region holds {@link #PATCH} and {@code assets}, named "Fast Belts". */
    static byte[] patchedMaze(DataAsset.ExternalAsset... assets) throws Exception{
        var loader = new MapLoader(Epoch.vanilla(), BuildingCodecs.builtIn());
        SaveFile save = MapCatalogTest.READER.read(MapCatalog.load(MapCatalogTest.jar(), null, MapCatalogTest.READER).find("maze").orElseThrow().read());
        WorldConfig template = WorldConfig.of(Epoch.vanilla(), 1, 1, 0).withKinds(KindRegistry.builtIn().withPassthrough());
        MapLoader.Loaded loaded = loader.loadMap(save, template, loader.hostRules(save, Gamemode.SURVIVAL));
        var all = new ArrayList<DataAsset>(List.of(new DataAsset.PatchAsset("patch-fast.json", PATCH)));
        all.addAll(List.of(assets));
        // embedded, as a map export writes them (MapIO.writeMap with embed): Shard has no asset cache to read hashes from
        loaded.state().patches = new DataAssets(all).write(true);
        loaded.state().tags.put("name", "Fast Belts");
        WorldStreamSource source = new WorldProjection(Epoch.vanilla(), BuildingCodecs.builtIn())
            .capture(loaded.world(), loaded.state(), PlayerState.fresh(0, "shard", VanillaIds.SHARDED));
        return MsavWriter.write(MsavWriter.meta(source, loaded.state().tags, VanillaIds.SHARDED, false, 0L), source);
    }
}
