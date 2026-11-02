package com.xpdustry.shard.server.host;

import com.xpdustry.shard.server.*;
import com.xpdustry.shard.server.config.*;
import com.xpdustry.shard.server.session.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;
import org.opentest4j.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Review 38's third finding: joins, confirms and leaves must reach the host however busy a tick is. Here one tick
 * admits 5,000 chat lines with 30 joins and 20 leaves; every route must progress or close, and every seat come back.
 * When the observer overflows anyway, the members in the world may have missed Calls, so they get the world again.
 */
class LifecycleTest{
    static final int SAYS = 5_000, JOINS = 30, LEAVES = 20;

    @TempDir
    Path temp;
    Server server;
    final List<TestClient> clients = new ArrayList<>();

    @BeforeEach
    void start() throws Exception{
        server = Server.start(new Server.Options(ServerTest.configure(temp, """
            host:
              worlds: []
            intents:
              inbox-capacity: 8192
            """), Map.of(ConfigKeys.NET_PLAYER_LIMIT.env(), "0"), true));
    }

    @AfterEach
    void stop() throws Exception{
        for(TestClient client : clients) client.close();
        server.close();
    }

    /** {@link ServerTest#await}, whose timeout names every session left, its state and world: which one didn't get there. */
    void await(String what, BooleanSupplier condition) throws InterruptedException{
        try{
            ServerTest.await(what, condition);
        }catch(AssertionFailedError e){
            throw new AssertionFailedError(e.getMessage() + "; sessions: " + sessions(), e);
        }
    }

    String sessions(){
        return server.sessions().all().stream().sorted(Comparator.comparingLong(Session::id))
            .map(s -> s.id() + " " + s.identity().plainName() + " " + s.state() + " " + (s.world() == null ? "-" : s.world().name()))
            .collect(Collectors.joining(", ", "[", "]"))
            // a session that vanished was closed by the transport, or left: these say which
            + "; transport: " + server.transport().stats().slowClients.sum() + " slow clients, " + server.transport().stats().rateClosed.sum()
            + " rate closes, " + server.transport().stats().deadlines.sum() + " deadlines";
    }

    Session session(String name){
        return server.sessions().all().stream().filter(s -> s.identity().plainName().equals(name)).findFirst().orElse(null);
    }

    TestClient connect(String name) throws Exception{
        var client = new TestClient(server.transport().address());
        clients.add(client);
        client.connect(name);
        return client;
    }

    TestClient playing(String name) throws Exception{
        TestClient client = connect(name);
        assertEquals(List.of("load Maze"), client.loads(1));
        client.confirm();
        await(name + " to play", () -> session(name) != null && session(name).state() == SessionState.IN_WORLD);
        return client;
    }

    @Test
    void aTickOfMoreEventsThanTheOldObserverHeldLosesNoRoute() throws Exception{
        HostedWorld maze = busyTick(0);
        assertEquals(List.of("load Maze"), clients.getFirst().loads(), "nothing was dropped, so nothing is sent again");
        everyoneLeaves(maze);
    }

    @Test
    void anObserverThatOverflowsAnywayIsRebuiltFromTheWorld() throws Exception{
        HostedWorld maze = busyTick(256);
        // the chatter was in the world when its Calls were dropped: it gets the world again, as /sync. A joiner gets
        // it again only if it was a member by then.
        assertEquals(List.of("load Maze", "begin", "load Maze"), clients.getFirst().loads(3));
        everyoneLeaves(maze);
    }

    /** One busy tick; returns the world once every route has progressed. The chatter is {@code clients}' first. */
    private HostedWorld busyTick(int eventCapacity) throws Exception{
        server.host().eventCapacity(eventCapacity);
        HostedWorld maze = server.host().host(server.host().spec("maze")).get(Hang.SECONDS, TimeUnit.SECONDS);
        playing("chatter");
        var leavers = new ArrayList<TestClient>();
        for(int i = 0; i < LEAVES; i++) leavers.add(playing("leaver" + i));
        Session chatter = session("chatter");

        // hold the world on its tick thread, with its inbox full of chat
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        CompletableFuture<Integer> blocked = maze.inspect(world -> {
            int offered = 0;
            for(int i = 0; i < SAYS; i++){
                var say = new IntentEnvelope(chatter.id(), chatter.routeGeneration(), 1_000_000L + i, 0, new com.xpdustry.shard.server.chat.ChatLine(com.xpdustry.shard.server.chat.ChatPolicy.VANILLA, "line " + i));
                if(world.inbox().offer(say)) offered++;
            }
            held.countDown();
            try{
                release.await(Hang.SECONDS, TimeUnit.SECONDS);
            }catch(InterruptedException e){
                Thread.currentThread().interrupt();
            }
            return offered;
        });
        assertTrue(held.await(Hang.SECONDS, TimeUnit.SECONDS));

        // meanwhile, joins and leaves wait in their mailboxes for the same tick
        var joiners = new ArrayList<TestClient>();
        for(int i = 0; i < JOINS; i++) joiners.add(connect("joiner" + i));
        await("the joins to queue", () -> server.sessions().all().stream().filter(s -> s.state() == SessionState.LOADING).count() == JOINS);
        for(TestClient leaver : leavers) leaver.close();
        await("the leaves to queue", () -> server.sessions().all().stream().filter(s -> s.state() == SessionState.CLOSED).count() == LEAVES);
        release.countDown();
        assertEquals(SAYS, blocked.get(Hang.SECONDS, TimeUnit.SECONDS));

        for(int i = 0; i < JOINS; i++) assertEquals("load Maze", joiners.get(i).loads(1).getFirst(), "joiner" + i);
        // a joiner that is a member by the time the observer overflows gets the world again too, as every member does
        // (W2.4h): under load, the world can fall behind until then. Each load is confirmed, as vanilla's client does.
        int[] confirmed = new int[JOINS];
        await("every joiner to play", () -> {
            for(int i = 0; i < JOINS; i++){
                try{
                    long loaded = joiners.get(i).loads().stream().filter(seen -> seen.startsWith("load ")).count();
                    for(; confirmed[i] < loaded; confirmed[i]++) joiners.get(i).confirm();
                }catch(IOException e){
                    throw new UncheckedIOException(e);
                }
            }
            return server.sessions().all().stream().filter(s -> s.state() == SessionState.IN_WORLD).count() == JOINS + 1;
        });
        assertEquals(JOINS + 1, server.sessions().size(), "every leaver forgotten");
        await("the host to count the players", () -> maze.players() == JOINS + 1);
        return maze;
    }

    private void everyoneLeaves(HostedWorld maze) throws Exception{
        for(TestClient client : clients) client.close();
        await("every session to close", () -> server.sessions().size() == 0);
        await("every seat back", () -> maze.mailbox().seats() == 0 && maze.players() == 0);
    }
}
