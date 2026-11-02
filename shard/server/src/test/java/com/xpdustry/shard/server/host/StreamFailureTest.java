package com.xpdustry.shard.server.host;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.server.*;
import com.xpdustry.shard.server.session.*;
import com.xpdustry.shard.wire.*;
import com.xpdustry.shard.wire.v160_7.Packets;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static com.xpdustry.shard.server.ServerTest.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * W2.13's request: a world stream the writer can't capture (W2.13 placed an unported block live, and the next join's
 * stream threw "cannot save a live-placed building") fails the load it was for, never the world. The joining client
 * is kicked with a reason, the players already in keep playing, and the capture's budget comes back.
 */
class StreamFailureTest{
    @TempDir
    static Path temp;
    static Server server;

    @BeforeAll
    static void start() throws Exception{
        server = Server.start(new Server.Options(ServerTest.configure(temp.resolve("main"), """
            host:
              worlds: [maze]
            """), Map.of(), false));
    }

    @AfterAll
    static void stop(){
        if(server != null) server.close();
    }

    @Test
    void aStreamThatCannotBeCapturedKicksTheJoinNotTheWorld() throws Exception{
        HostedWorld maze = server.host().world("maze").orElseThrow();
        try(var alpha = new TestClient(server.transport().address()); var bravo = new TestClient(server.transport().address())){
            alpha.connect("alpha");
            assertEquals(List.of("load Maze"), alpha.loads(1));
            alpha.confirm();
            await("alpha to play", () -> session("alpha") != null && session("alpha").state() == SessionState.IN_WORLD);

            maze.captureWith((world, state, players) -> {
                throw new IllegalStateException("cannot save a live-placed building");
            });
            bravo.connect("bravo");
            String reason = Codecs.readString(new Reads(bravo.payload(Packets.Kick.ID)));
            assertTrue(reason.contains("failed to send you the world"), reason);
            assertTrue(bravo.closedWithin(Hang.MILLIS), "bravo's connection closes");

            // the world goes on: it ticks, alpha is still in it, and the budget is back
            long ticks = maze.inspect(world -> world.ticks()).get(Hang.SECONDS, TimeUnit.SECONDS);
            await("maze to tick on", () -> maze.inspect(world -> world.ticks()).join() > ticks + 10);
            assertEquals(SessionState.IN_WORLD, session("alpha").state());
            assertFalse(alpha.closedWithin(0), "alpha stays");
            await("the capture's budget back", () -> server.host().captureBudget().reserved() == 0);

            // once the writer can capture again, the next load works
            maze.captureWith((world, state, players) -> server.host().sends().capture(world, state, players));
            alpha.chat("/sync");
            assertEquals(List.of("load Maze", "begin", "load Maze"), alpha.loads(3));
        }
    }

    private static Session session(String name){
        return server.sessions().all().stream().filter(s -> s.identity().plainName().equals(name)).findFirst().orElse(null);
    }
}
