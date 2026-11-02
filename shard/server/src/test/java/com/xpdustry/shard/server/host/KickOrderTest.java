package com.xpdustry.shard.server.host;

import com.xpdustry.shard.server.*;
import com.xpdustry.shard.server.intent.*;
import com.xpdustry.shard.server.session.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.v160_7.Packets;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static com.xpdustry.shard.server.ServerTest.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * W2.13's request: a kick goes out after the Calls its tick published before it, as vanilla's {@code player.kick}
 * follows what its handler already sent. The world's sync sends a tick's Calls after the host's own events, so a kick
 * carried out among those events closed the connection first, and the Calls before it were lost.
 */
class KickOrderTest{
    @TempDir
    static Path temp;
    static Server server;

    /** An intent that tells its player something, then kicks them, in one tick. */
    record CallThenKick(long session) implements Intent{
        @Override
        public Verdict apply(World world, IntentEnvelope envelope){
            world.publish(new IntentCall.To(session, new Packets.SendMessage("before the kick")));
            world.publish(new IntentCall.Kick(session, "after the message", 0));
            return Verdict.ADMITTED;
        }
    }

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
    void aKickFollowsTheCallsPublishedBeforeIt() throws Exception{
        HostedWorld maze = server.host().world("maze").orElseThrow();
        try(var client = new TestClient(server.transport().address())){
            client.connect("alpha");
            assertEquals(List.of("load Maze"), client.loads(1));
            client.confirm();
            Session session = server.sessions().all().getFirst();
            await("alpha to play", () -> session.state() == SessionState.IN_WORLD);

            assertTrue(maze.inspect(world -> world.inbox().offer(new IntentEnvelope(session.id(), session.routeGeneration(), 2_000_000L, 0,
                new CallThenKick(session.id())))).get(Hang.SECONDS, TimeUnit.SECONDS));
            List<String> seen = client.until(Packets.Kick.ID);
            assertEquals("kick after the message", seen.getLast());
            assertTrue(seen.contains("message before the kick"), "the message, then the kick: " + seen);
        }
    }
}
