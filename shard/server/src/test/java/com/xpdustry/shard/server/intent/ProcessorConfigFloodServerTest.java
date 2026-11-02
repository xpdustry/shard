package com.xpdustry.shard.server.intent;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.logic.*;
import com.xpdustry.shard.server.*;
import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.server.session.*;
import com.xpdustry.shard.sim.blocks.logic.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.v160_7.Packets;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Review 39, on the real server with the interaction rate limit off: a client floods a live processor with small
 * configs that would expand to gigabytes of link names. Each is refused and rolled back, the processor keeps its code,
 * both worlds keep their tick rate, and a player in the other world keeps getting state snapshots.
 */
class ProcessorConfigFloodServerTest{
    @TempDir
    static Path temp;
    static Server server;

    @BeforeAll
    static void start() throws Exception{
        server = Server.start(new Server.Options(ServerTest.configure(temp.resolve("flood"), """
            host:
              worlds: [maze, fortress]
            chat:
              anti-spam: false
            """), Map.of(), false));
    }

    @AfterAll
    static void stop(){
        if(server != null) server.close();
    }

    static Session session(String name){
        return server.sessions().all().stream().filter(s -> s.identity().plainName().equals(name)).findFirst().orElse(null);
    }

    static TestClient joined(String name) throws Exception{
        var client = new TestClient(server.transport().address());
        client.connect(name);
        client.world();
        client.confirm();
        ServerTest.await(name + " to play", () -> session(name) != null && session(name).state() == SessionState.IN_WORLD);
        return client;
    }

    @Test
    void aFloodOfExpandingConfigsIsRefusedAndBothWorldsKeepTicking() throws Exception{
        try(var alpha = joined("alpha"); var beta = joined("beta")){
            var flooded = (HostedWorld)session("alpha").world();
            var other = (HostedWorld)session("beta").world();
            assertNotSame(flooded, other, "the host puts the second player in the emptier world");
            int team = flooded.inspect(w -> Roster.of(w).player(session("alpha").id()).team).get(Hang.SECONDS, TimeUnit.SECONDS);
            int processor = flooded.inspect(w -> {
                var tiles = w.tiles();
                for(int pos = 0; pos < tiles.size(); pos++){
                    int x = tiles.x(pos), y = tiles.y(pos);
                    if(tiles.block(pos) != 0 || tiles.solid(pos) || tiles.isDeep(pos) || w.buildings().at(x, y) != null) continue;
                    Building built = w.buildings().setBlock(x, y, w.content().block("micro-processor"), team, 0, Cause.SERVER);
                    built.configureAny(LogicConfig.compress("set a 1", List.of()));
                    return built.pos();
                }
                throw new IllegalStateException("no free tile");
            }).get(Hang.SECONDS, TimeUnit.SECONDS);
            while(alpha.receives(Packets.TILE_CONFIG, 200)){
                // the server's own configureAny
            }
            beta.queue(Packets.STATE_SNAPSHOT).clear();

            // 300 names of 60,000 bytes each: 18 MB inflated, a few kilobytes on the wire
            var names = new ArrayList<LogicConfig.Link>();
            for(int i = 0; i < 300; i++) names.add(new LogicConfig.Link("a".repeat(60_000), 1, 0));
            byte[] payload = ConfigForwardServerTest.tileConfig(processor, LogicConfig.compress("set a 2", names));
            int count = 48;
            Pace pace = Pace.begin(flooded, other);
            for(int i = 0; i < count; i++) alpha.tcp(Packets.TileConfig.ID, payload);
            for(int i = 0; i < count; i++) alpha.payload(Packets.TILE_CONFIG);
            // play on after the last rollback, counted in ticks
            pace.play();

            assertFalse(alpha.receives(Packets.TILE_CONFIG, 200), "one rollback each");
            assertEquals("set a 1", flooded.inspect(w -> ((LogicBlock)w.buildings().at(Point2.x(processor), Point2.y(processor))).processor().code())
                .get(Hang.SECONDS, TimeUnit.SECONDS));
            assertEquals(SessionState.IN_WORLD, session("alpha").state(), "refused, not kicked");
            // both worlds keep ticking (unbounded, each config read 18 MB of names on the tick thread), and beta's world
            // keeps sending its state snapshots
            pace.assertKeptPace(beta.queue(Packets.STATE_SNAPSHOT).size());
        }
    }
}
