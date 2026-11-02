package com.xpdustry.shard.server.host;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.server.*;
import com.xpdustry.shard.server.session.*;
import com.xpdustry.shard.wire.Codecs;
import com.xpdustry.shard.wire.v160_7.Packets;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static com.xpdustry.shard.server.ServerTest.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Review 41's first finding: a route that picked a world before a replace took it off the host, and started after
 * the replace scanned the sessions, must not stay in the retired world. A connect paused once it picked the old world
 * resumes after the scan, either while the old world still ticks (its join reaches the old mailbox before the drain
 * can see the world empty) or once the old world retired; a transfer paused the same way resumes after the scan.
 */
class RouteRaceTest{
    static final String MAP_CHANGED = "[scarlet]The map changed while you were joining.[]\nPlease join again.";

    @TempDir
    Path temp;
    Server server;
    final List<TestClient> clients = new ArrayList<>();

    @BeforeEach
    void start() throws Exception{
        server = Server.start(new Server.Options(ServerTest.configure(temp, """
            host:
              worlds: [maze]
              join: first
            chat:
              show-connect-messages: false
            """), Map.of(), true));
    }

    @AfterEach
    void stop() throws Exception{
        server.host().hooks(Host.Hooks.NONE);
        for(TestClient client : clients) client.close();
        server.close();
    }

    /** Holds one routing thread at a hook until {@link #resume}. */
    static final class Pause{
        final CountDownLatch reached = new CountDownLatch(1), resumed = new CountDownLatch(1);

        void hold(){
            if(reached.getCount() == 0) return;
            reached.countDown();
            try{
                assertTrue(resumed.await(Hang.SECONDS, TimeUnit.SECONDS));
            }catch(InterruptedException e){
                Thread.currentThread().interrupt();
            }
        }

        void reached() throws InterruptedException{
            assertTrue(reached.await(Hang.SECONDS, TimeUnit.SECONDS), "the route reached the hook");
        }

        void resume(){
            resumed.countDown();
        }
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

    TestClient playing(String name, String map) throws Exception{
        TestClient client = connect(name);
        assertEquals(List.of("load " + map), client.loads(1));
        client.confirm();
        await(name + " to play", () -> session(name) != null && session(name).state() == SessionState.IN_WORLD);
        return client;
    }

    MapCatalog.Entry map(String name){
        return server.host().maps().find(name).orElseThrow();
    }

    /** Pauses the connect of {@code name} once it picked {@code world} among its candidates. */
    Pause pauseJoin(String name, HostedWorld world){
        var pause = new Pause();
        server.host().hooks(new Host.Hooks(){
            @Override
            public void picked(Session session, List<HostedWorld> candidates){
                if(session.identity().plainName().equals(name) && candidates.contains(world)) pause.hold();
            }
        });
        return pause;
    }

    static String kick(TestClient client) throws InterruptedException{
        byte[] body = client.payload(Packets.Kick.ID);
        return Codecs.readString(new Reads(body));
    }

    /** {@code client}, in a world when {@code old} was replaced, loads {@code next} over its connection and plays there. */
    void moved(String name, TestClient client, HostedWorld next, String map) throws Exception{
        List<String> loads = client.loads(3);
        assertEquals(List.of("begin", "load " + map), loads.subList(1, 3), loads.toString());
        client.confirm();
        await(name + " to play in " + map, () -> session(name).world() == next && session(name).state() == SessionState.IN_WORLD);
    }

    /** Nothing is left on {@code old}: no session routed there, no seat taken, and the manager runs its replacement. */
    void nothingLeftOn(HostedWorld old) throws Exception{
        await("no session routed to the old world", () -> server.sessions().all().stream().noneMatch(s -> s.world() == old));
        await("every seat of the old world back", () -> old.mailbox().seats() == 0);
        assertTrue(old.mailbox().released(), "the old world takes no route");
        assertNotSame(old.managed(), server.host().manager().get(old.id()).orElse(null));
    }

    @Test
    void aLateJoinThatReachesTheOldWorldBeforeItsDrainIsKicked() throws Exception{
        HostedWorld maze = server.host().world("maze").orElseThrow();
        TestClient stay = playing("stay", "Maze");
        Pause pause = pauseJoin("late", maze);
        TestClient late = connect("late");
        pause.reached();

        // the old world holds its tick, with stay a member: no drain inspection can see it empty before the late join
        CountDownLatch release = hold(maze);
        // its scan of the sessions is over once replace returns
        CompletableFuture<HostedWorld> replacing = server.host().replace(maze, map("fortress"), maze.mode());
        pause.resume();
        assertEquals(MAP_CHANGED, kick(late));
        assertTrue(late.closedWithin(Hang.MILLIS));
        release.countDown();

        HostedWorld fortress = replacing.get(Hang.SECONDS, TimeUnit.SECONDS);
        moved("stay", stay, fortress, "Fortress");
        nothingLeftOn(maze);
    }

    @Test
    void aLateJoinOnceTheOldWorldRetiredJoinsItsReplacement() throws Exception{
        HostedWorld maze = server.host().world("maze").orElseThrow();
        Pause pause = pauseJoin("late", maze);
        TestClient late = connect("late");
        pause.reached();

        // nobody plays in maze: it drains, retires and is replaced before the join goes on
        HostedWorld fortress = server.host().replace(maze, map("fortress"), maze.mode()).get(Hang.SECONDS, TimeUnit.SECONDS);
        pause.resume();
        assertEquals(List.of("load Fortress"), late.loads(1));
        late.confirm();
        await("late to play in fortress", () -> session("late") != null && session("late").world() == fortress
            && session("late").state() == SessionState.IN_WORLD);
        nothingLeftOn(maze);
    }

    /** Holds {@code world}'s tick thread until the latch returned is counted down. */
    static CountDownLatch hold(HostedWorld world) throws InterruptedException{
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        world.inspect(w -> {
            held.countDown();
            try{
                release.await(Hang.SECONDS, TimeUnit.SECONDS);
            }catch(InterruptedException e){
                Thread.currentThread().interrupt();
            }
            return null;
        });
        assertTrue(held.await(Hang.SECONDS, TimeUnit.SECONDS));
        return release;
    }

    /** Starts moving mover to {@code side} on another thread, paused once it found {@code side} available. */
    CompletableFuture<Boolean> pauseTransfer(HostedWorld side, Pause pause) throws InterruptedException{
        server.host().hooks(new Host.Hooks(){
            @Override
            public void moving(Session session, HostedWorld to){
                if(to == side) pause.hold();
            }
        });
        CompletableFuture<Boolean> moved = CompletableFuture.supplyAsync(() -> server.host().transfer(session("mover"), side));
        pause.reached();
        return moved;
    }

    @Test
    void aTransferThatPickedTheOldWorldBeforeItsDrainIsKicked() throws Exception{
        HostedWorld side = server.host().host(server.host().spec("side=fortress")).get(Hang.SECONDS, TimeUnit.SECONDS);
        TestClient mover = playing("mover", "Maze");
        var pause = new Pause();
        CompletableFuture<Boolean> moved = pauseTransfer(side, pause);

        // side holds its tick: it can't drain and retire before the transfer takes its seat there
        CountDownLatch release = hold(side);
        CompletableFuture<HostedWorld> replacing = server.host().replace(side, map("maze"), side.mode());
        pause.resume();
        assertFalse(moved.get(Hang.SECONDS, TimeUnit.SECONDS), "the transfer didn't happen");
        assertEquals(MAP_CHANGED, kick(mover));
        assertTrue(mover.closedWithin(Hang.MILLIS));
        release.countDown();
        replacing.get(Hang.SECONDS, TimeUnit.SECONDS);
        nothingLeftOn(side);
    }

    @Test
    void aTransferOnceTheOldWorldRetiredIsRefused() throws Exception{
        HostedWorld side = server.host().host(server.host().spec("side=fortress")).get(Hang.SECONDS, TimeUnit.SECONDS);
        TestClient mover = playing("mover", "Maze");
        var pause = new Pause();
        CompletableFuture<Boolean> moved = pauseTransfer(side, pause);

        server.host().replace(side, map("maze"), side.mode()).get(Hang.SECONDS, TimeUnit.SECONDS);
        pause.resume();
        assertFalse(moved.get(Hang.SECONDS, TimeUnit.SECONDS), "the retired world gives no seat");
        // the mover stays where it plays
        assertFalse(mover.closedWithin(500));
        assertEquals(SessionState.IN_WORLD, session("mover").state());
        assertEquals("maze", session("mover").world().name());
        nothingLeftOn(side);
    }
}
