package com.xpdustry.shard.server.host;

import com.xpdustry.shard.server.*;
import com.xpdustry.shard.server.session.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static com.xpdustry.shard.server.ServerTest.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Review 38's first finding: a world stream belongs to one load, and a newer load fences it. The clients here play
 * what they receive as vanilla's does ({@link TestClient#loads}): every stream they complete is a load they confirm.
 */
class WorldStreamFenceTest{
    @TempDir
    static Path temp;
    static Server server;

    @BeforeAll
    static void start() throws Exception{
        server = Server.start(new Server.Options(ServerTest.configure(temp.resolve("main"), """
            host:
              worlds: [maze, fortress]
              join: first
            """), Map.of(), false));
    }

    @AfterAll
    static void stop(){
        if(server != null) server.close();
    }

    @AfterEach
    void settle() throws Exception{
        for(HostedWorld world : server.host().worlds()) world.encodeOn(null);
        await("the sessions to close", () -> server.sessions().size() == 0);
    }

    static HostedWorld world(String id){
        return server.host().world(id).orElseThrow();
    }

    static Session session(String name){
        return server.sessions().all().stream().filter(s -> s.identity().plainName().equals(name)).findFirst().orElse(null);
    }

    /** Connects, loads maze and confirms it. */
    static TestClient playing(String name) throws Exception{
        var client = new TestClient(server.transport().address());
        client.connect(name);
        assertEquals(List.of("load Maze"), client.loads(1));
        client.confirm();
        await(name + " to play", () -> session(name) != null && session(name).state() == SessionState.IN_WORLD);
        return client;
    }

    static boolean member(HostedWorld world, Session session) throws Exception{
        return world.inspect(w -> w.members().contains(session.id())).get(Hang.SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Whether {@code world}'s roster has added the session's player. That is a tick after the session goes
     * {@code IN_WORLD}, which it does when the confirm is offered ({@link Session#confirm}).
     */
    static boolean added(HostedWorld world, Session session){
        return world.inspect(w -> Roster.of(w).player(session.id()).isAdded()).orTimeout(Hang.SECONDS, TimeUnit.SECONDS).join();
    }

    /**
     * A window counted in the world's ticks: after it, the world has run {@code ticks} more, so it has acted on whatever
     * input was offered before. A sleep of the same length measures how busy the machine is.
     */
    static void ticksOf(HostedWorld world, int ticks) throws Exception{
        long start = world.inspect(w -> w.ticks()).get(Hang.SECONDS, TimeUnit.SECONDS);
        await(ticks + " ticks of " + world.id(), () -> world.inspect(w -> w.ticks()).orTimeout(Hang.SECONDS, TimeUnit.SECONDS).join() >= start + ticks);
    }

    @Test
    void aStreamDeflatedAfterATransferIsNeverSent() throws Exception{
        try(TestClient client = playing("alpha")){
            Session session = session("alpha");
            var held = new LinkedBlockingQueue<Runnable>();
            world("maze").encodeOn(held::add);
            client.chat("/sync");
            Runnable sync = held.poll(Hang.SECONDS, TimeUnit.SECONDS);
            assertNotNull(sync, "maze captured the /sync and holds its deflate");

            client.chat("/world fortress");
            // the client still waits for the /sync's stream: fortress's answers that worldDataBegin, and no other
            // comes, since vanilla's client would replay it after loading fortress
            assertEquals(List.of("load Maze", "begin", "load Fortress"), client.loads(3));
            sync.run();
            client.confirm();
            await("alpha to play in fortress", () -> session.state() == SessionState.IN_WORLD);
            // anything queued for the client has been written once the server answers a ping
            client.barrier();
            assertEquals(List.of("load Maze", "begin", "load Fortress"), client.loads(), "one destination load, and nothing after it");
            assertSame(world("fortress"), session.world());
            assertTrue(member(world("fortress"), session));
            assertFalse(member(world("maze"), session));
        }
    }

    @Test
    void aConfirmOfAStreamFromBeforeATransferConfirmsNothing() throws Exception{
        try(TestClient client = playing("beta")){
            Session session = session("beta");
            client.chat("/sync");
            // the client has maze again but hasn't confirmed it when the transfer starts
            assertEquals(List.of("load Maze", "begin", "load Maze"), client.loads(3));
            client.chat("/world fortress");
            await("beta to load fortress", () -> session.state() == SessionState.LOADING && session.world() == world("fortress"));
            client.confirm();
            // the confirm is read before the answer to a ping sent after it, and the world has 30 ticks to act on it
            client.barrier();
            ticksOf(world("fortress"), 30);
            assertEquals(SessionState.LOADING, session.state(), "the confirm was the sync's, not fortress's");
            assertFalse(added(world("fortress"), session));

            assertEquals(List.of("load Maze", "begin", "load Maze", "begin", "load Fortress"), client.loads(5));
            client.confirm();
            await("beta to play in fortress", () -> session.state() == SessionState.IN_WORLD && added(world("fortress"), session));
            assertEquals(1, session.staleDropped());
        }
    }

    @Test
    void aConfirmWithoutAStreamIsStale() throws Exception{
        try(TestClient client = playing("gamma")){
            Session session = session("gamma");
            client.confirm();
            await("the extra confirm to count as stale", () -> session.staleDropped() == 1);
            assertEquals(SessionState.IN_WORLD, session.state());
        }
    }
}
