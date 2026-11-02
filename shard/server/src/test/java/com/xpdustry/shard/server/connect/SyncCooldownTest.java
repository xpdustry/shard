package com.xpdustry.shard.server.connect;

import com.xpdustry.shard.server.*;
import com.xpdustry.shard.server.sched.*;
import com.xpdustry.shard.server.session.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.nio.file.*;
import java.util.*;

import static com.xpdustry.shard.server.ServerTest.await;
import static org.junit.jupiter.api.Assertions.*;

/** Vanilla's {@code /sync}: the world again, once every five seconds, on the chat's clock rather than the machine's. */
class SyncCooldownTest{
    @TempDir
    static Path temp;
    static Server server;

    @BeforeAll
    static void start() throws Exception{
        server = Server.start(new Server.Options(ServerTest.configure(temp.resolve("main"), """
            host:
              worlds: [maze]
            """), Map.of(), true));
    }

    @AfterAll
    static void stop(){
        if(server != null) server.close();
    }

    @Test
    void syncResendsTheWorldOnceEveryFiveSeconds() throws Exception{
        var clock = new ManualClock();
        server.chat().clock(clock);
        try(var client = new TestClient(server.transport().address())){
            client.connect("alpha");
            assertEquals(List.of("load Maze"), client.loads(1));
            client.confirm();
            await("alpha to play", () -> server.sessions().all().stream().anyMatch(s -> s.state() == SessionState.IN_WORLD));

            client.chat("/sync");
            assertEquals(List.of("load Maze", "begin", "load Maze"), client.loads(3));
            client.confirm();
            client.chat("/sync");
            client.message("[scarlet]You may only /sync every 5 seconds.");
            clock.advance(4_999_000_000L);
            client.chat("/sync");
            client.message("[scarlet]You may only /sync every 5 seconds.");
            assertEquals(3, client.loads().size(), "no world while it cools down");
            clock.advance(1_000_000L);
            client.chat("/sync");
            assertEquals(List.of("load Maze", "begin", "load Maze", "begin", "load Maze"), client.loads(5));
        }finally{
            server.chat().clock(NanoClock.SYSTEM);
        }
    }
}
