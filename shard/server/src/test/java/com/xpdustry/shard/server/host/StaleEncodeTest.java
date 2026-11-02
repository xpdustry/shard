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
 * Review 39's stale-encoder finding: an encode checks its load is current before it starts, but a transfer can
 * replace the load while it runs. Its failure then belongs to the old load, and must not close the session on its new
 * route; a failure of the current load still closes that session, and only it.
 */
class StaleEncodeTest{
    @TempDir
    static Path temp;
    static Server server;

    @BeforeAll
    static void start() throws Exception{
        server = Server.start(new Server.Options(ServerTest.configure(temp.resolve("main"), """
            host:
              worlds: [maze, fortress]
            """), Map.of(), false));
    }

    @AfterAll
    static void stop(){
        if(server != null) server.close();
    }

    @Test
    void anOldLoadsFailingEncodeLeavesTheNewRouteAlone() throws Exception{
        HostedWorld maze = server.host().world("maze").orElseThrow();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var thrown = new CountDownLatch(1);
        try(var alpha = new TestClient(server.transport().address()); var bravo = new TestClient(server.transport().address())){
            alpha.connect("alpha");
            assertEquals(List.of("load Maze"), alpha.loads(1));
            alpha.confirm();
            Session session = session("alpha");
            await("alpha to play", () -> session.state() == SessionState.IN_WORLD);

            // alpha's /sync: its encode passes its load check, then waits, then fails
            maze.encodeWith(source -> {
                entered.countDown();
                try{
                    release.await(Hang.SECONDS, TimeUnit.SECONDS);
                }catch(InterruptedException e){
                    Thread.currentThread().interrupt();
                }
                thrown.countDown();
                throw new IllegalStateException("the old load's encode fails");
            });
            alpha.chat("/sync");
            assertTrue(entered.await(Hang.SECONDS, TimeUnit.SECONDS), "the /sync's encode started");
            // meanwhile, a transfer replaces that load
            alpha.chat("/world fortress");
            assertEquals(List.of("load Maze", "begin", "load Fortress"), alpha.loads(3));
            alpha.confirm();
            await("alpha to play in fortress", () -> session.state() == SessionState.IN_WORLD && session.world() == server.host().world("fortress").orElseThrow());

            release.countDown();
            assertTrue(thrown.await(Hang.SECONDS, TimeUnit.SECONDS));
            assertFalse(alpha.closedWithin(1_000), "the old load's failure leaves alpha connected");
            assertEquals(SessionState.IN_WORLD, session.state());

            // a failure of the current load closes that session, and only it
            maze.encodeWith(source -> {
                throw new IllegalStateException("the current load's encode fails");
            });
            bravo.connect("bravo");
            String reason = Codecs.readString(new Reads(bravo.payload(Packets.Kick.ID)));
            assertTrue(reason.contains("failed to send you the world"), reason);
            assertTrue(bravo.closedWithin(Hang.MILLIS));
            assertFalse(alpha.closedWithin(500), "alpha stays");
        }finally{
            release.countDown();
            maze.encodeWith(null);
        }
    }

    private static Session session(String name){
        return server.sessions().all().stream().filter(s -> s.identity().plainName().equals(name)).findFirst().orElse(null);
    }
}
