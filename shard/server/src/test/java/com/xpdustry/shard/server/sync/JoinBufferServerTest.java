package com.xpdustry.shard.server.sync;

import com.xpdustry.shard.server.*;
import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.server.session.*;
import com.xpdustry.shard.sim.building.Building;
import com.xpdustry.shard.sim.building.ContentRef;
import com.xpdustry.shard.sim.world.Cause;
import com.xpdustry.shard.wire.v160_7.Packets;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Per-join buffering on the real server: while one client loads a world, the world changes and the players get
 * the Calls; the loader gets none until its confirm, then all of them, in order, the same bytes.
 */
class JoinBufferServerTest{
    @TempDir
    static Path temp;
    static Server server;

    @BeforeAll
    static void start() throws Exception{
        server = Server.start(new Server.Options(ServerTest.configure(temp.resolve("sync"), """
            host:
              worlds: [maze]
            """), Map.of(), false));
    }

    @AfterAll
    static void stop(){
        if(server != null) server.close();
    }

    static Session session(String name){
        return server.sessions().all().stream().filter(s -> s.identity().plainName().equals(name)).findFirst().orElse(null);
    }

    static TestClient client() throws Exception{
        return new TestClient(server.transport().address());
    }

    @Test
    void callsMadeWhileAClientLoadsReachItAfterItsConfirmInOrder() throws Exception{
        try(var alpha = client(); var beta = client()){
            alpha.connect("alpha");
            alpha.world();
            alpha.confirm();
            ServerTest.await("alpha to play", () -> session("alpha") != null && session("alpha").state() == SessionState.IN_WORLD);
            var world = (HostedWorld)session("alpha").world();

            beta.connect("beta");
            beta.world();
            assertEquals(SessionState.LOADING, session("beta").state());

            // a sorter set to five items in turn, then destroyed
            int items = 5;
            world.inspect(w -> {
                var tiles = w.tiles();
                for(int pos = 0; pos < tiles.size(); pos++){
                    if(tiles.block(pos) != 0 || tiles.solid(pos) || tiles.isDeep(pos) || w.buildings().at(tiles.x(pos), tiles.y(pos)) != null) continue;
                    Building sorter = w.buildings().setBlock(tiles.x(pos), tiles.y(pos), w.content().block("sorter"), 1, 0, Cause.SERVER);
                    for(int item = 0; item < items; item++) w.buildings().configure(sorter, null, ContentRef.item(item), Cause.SERVER);
                    w.buildings().kill(sorter);
                    return pos;
                }
                throw new IllegalStateException("no free tile");
            }).get(Hang.SECONDS, TimeUnit.SECONDS);

            var configs = new ArrayList<byte[]>();
            for(int i = 0; i < items; i++) configs.add(alpha.payload(Packets.TILE_CONFIG));
            byte[] destroyed = alpha.payload(Packets.BUILD_DESTROYED);
            assertFalse(beta.receives(Packets.TILE_CONFIG, 300), "held while beta loads");
            assertFalse(beta.receives(Packets.BUILD_DESTROYED, 0));

            beta.confirm();
            ServerTest.await("beta to play", () -> session("beta").state() == SessionState.IN_WORLD);
            for(int i = 0; i < items; i++) assertArrayEquals(configs.get(i), beta.payload(Packets.TILE_CONFIG), "config " + i);
            assertArrayEquals(destroyed, beta.payload(Packets.BUILD_DESTROYED));
            // Outbox.confirmed sends the frames before it counts them, so beta can read them all first
            ServerTest.await("the flush count", () -> world.outbox().stats().flushed() >= items + 1);
        }
    }
}
