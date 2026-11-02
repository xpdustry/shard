package com.xpdustry.shard.server.connect;

import com.xpdustry.shard.net.*;
import com.xpdustry.shard.server.*;
import com.xpdustry.shard.server.config.*;
import com.xpdustry.shard.server.session.*;
import com.xpdustry.shard.wire.v160_7.KickReason;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

import static com.xpdustry.shard.server.ServerTest.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Review 38's fifth and sixth findings, with connects held at the flow's {@link ConnectFlow.Hooks}: admission is
 * atomic, a ban published before activation keeps the session out, and a disconnect at any point of the connect
 * ends the join's record.
 */
class AdmissionRaceTest{
    @TempDir
    static Path temp;
    static Server server;
    static final Map<String, String> ENV = new ConcurrentHashMap<>();
    final List<TestClient> clients = new ArrayList<>();

    @BeforeAll
    static void start() throws Exception{
        server = Server.start(new Server.Options(ServerTest.configure(temp.resolve("main"), """
            host:
              worlds: [maze]
            """), ENV, true));
    }

    @AfterAll
    static void stop(){
        if(server != null) server.close();
    }

    @AfterEach
    void settle() throws Exception{
        server.flow().hooks(ConnectFlow.Hooks.NONE);
        for(TestClient client : clients) client.close();
        await("the sessions to close", () -> server.sessions().size() == 0);
        if(!ENV.isEmpty()){
            ENV.clear();
            server.config().reload();
        }
    }

    TestClient client() throws Exception{
        var client = new TestClient(server.transport().address());
        clients.add(client);
        return client;
    }

    /** Holds the first {@code parties} connects that pass the checks until all of them have. */
    static ConnectFlow.Hooks together(int parties){
        var barrier = new CyclicBarrier(parties);
        return new ConnectFlow.Hooks(){
            @Override
            public void checked(Conn conn){
                try{
                    barrier.await(Hang.SECONDS, TimeUnit.SECONDS);
                }catch(Exception e){
                    // a connect that never comes: the test fails on its counts
                }
            }
        };
    }

    /** Waits for both clients' fates: the ones that got a world, and the kick reasons of the others. */
    static List<Object> fates(TestClient... clients) throws Exception{
        var out = new ArrayList<Object>();
        for(TestClient client : clients){
            if(client.receives(com.xpdustry.shard.wire.v160_7.Packets.STREAM_BEGIN, 3_000)) out.add("joined");
            else out.add(KickReason.of(client.kickReason()));
        }
        out.sort(Comparator.comparing(Object::toString));
        return out;
    }

    @Test
    void twoConnectsWithOneUuidAdmitOne() throws Exception{
        server.flow().hooks(together(2));
        TestClient first = client(), second = client();
        var id = TestClient.Connect.named("twin");
        first.connect(id.packet());
        second.connect(new TestClient.Connect(id.build(), id.type(), "twin2", id.usid(), id.id(), id.mods()).packet());
        assertEquals(List.of(KickReason.idInUse, "joined"), fates(first, second));
        assertEquals(1, server.sessions().all().stream().filter(s -> s.state() != SessionState.CLOSED).count());
    }

    @Test
    void twoConnectsForTheLastSlotAdmitOne() throws Exception{
        ENV.put(ConfigKeys.NET_PLAYER_LIMIT.env(), "1");
        server.config().reload();
        server.flow().hooks(together(2));
        TestClient first = client(), second = client();
        first.connect("slot1");
        second.connect("slot2");
        assertEquals(List.of("joined", KickReason.playerLimit), fates(first, second));
        assertEquals(1, server.flow().admitted());
    }

    @Test
    void aBanPublishedBeforeActivationKeepsTheSessionOut() throws Exception{
        String uuid = TestClient.Connect.named("banned").packet().uuidString();
        server.flow().hooks(new ConnectFlow.Hooks(){
            @Override
            public void opened(Session session){
                server.admin().banPlayer(uuid, false, "", null, "test").join();
            }
        });
        try{
            TestClient client = client();
            client.connect("banned");
            assertEquals(List.of(KickReason.banned), fates(client));
            assertTrue(client.closedWithin(Hang.MILLIS));
            await("the place to come back", () -> server.flow().admitted() == 0);
        }finally{
            server.admin().unbanPlayer(uuid, "test").join();
            // a ban's kick has vanilla's cooldown, on the address too
            server.admin().pardon(uuid);
        }
    }

    @Test
    void aDisconnectBeforeTheJoinIsRecordedStillEndsTheRecord() throws Exception{
        server.flow().hooks(new ConnectFlow.Hooks(){
            @Override
            public void routed(Session session){
                session.conn().close("gone");
                try{
                    await("the disconnect", () -> session.state() == SessionState.CLOSED);
                }catch(InterruptedException e){
                    Thread.currentThread().interrupt();
                }
            }
        });
        TestClient client = client();
        client.connect("fleeting");
        assertTrue(client.closedWithin(Hang.MILLIS));
        String uuid = TestClient.Connect.named("fleeting").packet().uuidString();
        await("the record to end", () -> ended(uuid));
        assertEquals(0, server.flow().admitted());
    }

    /** Whether the player's sessions in the database have all ended. */
    static boolean ended(String uuid){
        try(var db = DriverManager.getConnection("jdbc:sqlite:" + temp.resolve("main/data/shard.db"));
            var query = db.prepareStatement("SELECT count(*), count(s.ended_at) FROM sessions s JOIN players p ON p.id = s.player_id WHERE p.uuid = ?")){
            query.setString(1, uuid);
            try(var rows = query.executeQuery()){
                rows.next();
                return rows.getInt(1) > 0 && rows.getInt(1) == rows.getInt(2);
            }
        }catch(SQLException e){
            throw new AssertionError(e);
        }
    }
}
