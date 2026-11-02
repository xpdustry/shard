package com.xpdustry.shard.server;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.net.Frame;
import com.xpdustry.shard.server.config.*;
import com.xpdustry.shard.server.console.Console;
import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.server.session.*;
import com.xpdustry.shard.server.store.Bans;
import com.xpdustry.shard.wire.Codecs;
import com.xpdustry.shard.wire.v160_7.KickReason;
import com.xpdustry.shard.wire.v160_7.Packets;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code shard} server end to end over loopback, with byte-level clients: the connect flow into a hosted map,
 * {@code connectConfirm}, heartbeats, chat, {@code /sync}, a transfer between worlds and the leave, and every
 * {@link KickReason} a connect packet can earn.
 */
public class ServerTest{
    @TempDir
    static Path temp;
    /** The environment the config reloads read: tests switch admin settings through it. */
    static final Map<String, String> ENV = new ConcurrentHashMap<>();
    static Server server;
    /** How far the server's transport clock runs ahead of {@link System#nanoTime}: a test moves it past a timeout. */
    static final AtomicLong NET_AHEAD = new AtomicLong();

    @BeforeAll
    static void start() throws Exception{
        server = Server.start(new Server.Options(configure(temp.resolve("main"), """
            host:
              worlds: [maze, fortress]
              join: first
            """), ENV, false, () -> System.nanoTime() + NET_AHEAD.get()));
    }

    @AfterAll
    static void stop(){
        if(server != null) server.close();
    }

    @AfterEach
    void settle() throws Exception{
        // every test leaves the host as it found it: nobody connected, no cooldown, no setting changed
        await("the sessions to close", () -> server.sessions().size() == 0);
        if(!ENV.isEmpty()){
            ENV.clear();
            server.config().reload();
        }
        // a cooldown left behind would turn every later connect into recentKick for 30 s: the test that left it fails
        long cooldown = server.admin().kickedUntil("", "127.0.0.1") - System.currentTimeMillis();
        server.admin().pardonAll();
        assertTrue(cooldown <= 0, "the test left a kick cooldown on 127.0.0.1 (" + cooldown + " ms)");
    }

    /** The vanilla jar on the test class path, whose default maps the server hosts. */
    public static String vanillaJar() throws Exception{
        String url = Objects.requireNonNull(ServerTest.class.getClassLoader().getResource("maps/default/fortress.msav")).toString();
        return Path.of(URI.create(url.substring("jar:".length(), url.indexOf("!/")))).toString();
    }

    /** A config directory under {@code root}: loopback on a free port, storage under it, and {@code extra}. */
    public static Path configure(Path root, String extra) throws Exception{
        Path data = root.resolve("data");
        Files.createDirectories(data);
        Files.writeString(root.resolve("shard.yml"), """
            net:
              port: 0
            ops:
              enabled: false
            log:
              format: text
            admin:
              uuid-change-limit: 0
            storage:
              database: "%s"
              backup-dir: "%s"
            persistence:
              directory: "%s"
              saves-directory: "%s"
            maps:
              vanilla-jar: "%s"
              directory: "%s"
            """.formatted(data.resolve("shard.db"), data.resolve("backups"), data.resolve("worlds"), data.resolve("saves"), vanillaJar(), root.resolve("maps")) + extra);
        return root;
    }

    static void set(ConfigKey<?> key, String value) throws ConfigException{
        ENV.put(key.env(), value);
        server.config().reload();
    }

    static TestClient client() throws Exception{
        return new TestClient(server.transport().address());
    }

    public static void await(String what, BooleanSupplier condition) throws InterruptedException{
        long deadline = System.nanoTime() + TestClient.WAIT.toNanos();
        while(!condition.getAsBoolean()){
            if(System.nanoTime() > deadline) fail("timed out waiting for " + what);
            Thread.sleep(10);
        }
    }

    /** Ticks {@code server} has run so far, over every world. */
    public static long ticks(Server server){
        return server.scheduler().slots().stream().mapToLong(slot -> slot.stats().ticks()).sum();
    }

    static Session session(String name){
        return server.sessions().all().stream().filter(s -> s.identity().plainName().equals(name)).findFirst().orElse(null);
    }

    static String string(byte[] payload){
        return Codecs.readString(new Reads(payload));
    }

    /** Connects as {@code name} and joins: the world stream, then the confirm. */
    static TestClient.WorldData join(TestClient client, String name) throws Exception{
        client.connect(name);
        TestClient.WorldData world = client.world();
        client.confirm();
        await(name + " to play", () -> session(name) != null && session(name).state() == SessionState.IN_WORLD);
        client.message("[accent]" + name + "[accent] has connected.");
        return world;
    }

    // ---- the flow

    @Test
    void aClientJoinsAMapConfirmsAndHearsHeartbeats() throws Exception{
        try(var client = client()){
            client.connect("alpha");
            TestClient.WorldData world = client.world();
            assertEquals("Maze", world.tags().get("name"));
            assertTrue(world.rules().startsWith("{"), world.rules());
            Session session = session("alpha");
            assertEquals("maze", session.world().name());
            assertEquals(SessionState.LOADING, session.state());
            assertEquals(world.player(), ((HostedWorld)session.world()).inspect(w -> Roster.of(w).player(session.id()).id()).get());

            client.confirm();
            assertEquals("[accent]alpha[accent] has connected.", string(client.payload(Packets.SEND_MESSAGE)));
            // the client times out after 20 s without one; the confirmed player is among its entities (with the weather)
            var snapshot = ByteBuffer.wrap(client.payload(Packets.ENTITY_SNAPSHOT));
            assertTrue(snapshot.getShort() >= 1);
            assertEquals(SessionState.IN_WORLD, session.state());
            assertTrue(((HostedWorld)session.world()).inspect(w -> Roster.of(w).player(session.id()).isAdded()).get());

            client.tcp(Packets.PING, new byte[]{0, 0, 0, 0, 0, 0, 0, 42});
            assertArrayEquals(new byte[]{0, 0, 0, 0, 0, 0, 0, 42}, client.payload(Packets.PING_RESPONSE));
        }
    }

    @Test
    void aQuietClientOutlivesTheReadTimeout() throws Exception{
        try(var client = client(); var canary = client()){
            join(client, "quiet");
            join(canary, "canary");
            client.stopKeepingAlive();
            canary.stopKeepingAlive();
            // 14 s on the transport's clock, past the server's 12 s read timeout (Arc's), with nothing sent but a
            // keepalive every 2 s, as a stock client waits in a world; a test client that didn't send them was closed,
            // with nothing logged when quiet. The canary sends nothing at all.
            for(int second = 2; second <= 14; second += 2){
                client.keepAliveNow();
                NET_AHEAD.addAndGet(2_000_000_000L);
            }
            // the canary's close shows that a sweep ran past the timeout; a quiet client closed in it would miss the ping
            assertTrue(canary.closedWithin(Hang.MILLIS), "the canary outlived the read timeout: no sweep at the moved clock");
            client.tcp(Packets.PING, new byte[8]);
            long deadline = System.nanoTime() + Hang.NANOS;
            while(client.queue(Packets.PING_RESPONSE).isEmpty() && !client.closedWithin(10) && System.nanoTime() < deadline){
                // the answer, or the close
            }
            assertFalse(client.closedWithin(0), "the server closed the quiet client");
            assertEquals(SessionState.IN_WORLD, session("quiet").state());
        }
    }

    @Test
    void twoClientsShareAWorldAndOneRejoins() throws Exception{
        try(var alpha = client()){
            join(alpha, "alpha");
            int beta;
            try(var client = client()){
                beta = join(client, "beta").player();
                assertEquals(session("alpha").world(), session("beta").world());

                client.chat("hello");
                var chat = new Reads(alpha.payload(Packets.SEND_MESSAGE2));
                assertEquals("[coral][[[#D37FFFFF]beta[coral]]:[white] hello", Codecs.readString(chat));
                assertEquals("hello", Codecs.readString(chat));
                assertEquals(beta, chat.i());
            }
            assertEquals(beta, ByteBuffer.wrap(alpha.payload(Packets.PLAYER_DISCONNECT)).getInt());
            alpha.message("[accent]beta[accent] has disconnected.");
            await("beta's session to close", () -> session("beta") == null);

            try(var client = client()){
                int again = join(client, "beta").player();
                assertNotEquals(beta, again, "a new player");
                alpha.message("[accent]beta[accent] has connected.");
                assertEquals(2, ((HostedWorld)session("alpha").world()).inspect(w -> Roster.of(w).size()).get());
            }
        }
    }

    @Test
    void worldMovesTheSessionOverTheSameConnection() throws Exception{
        try(var client = client()){
            join(client, "alpha");
            Session session = session("alpha");
            var maze = (HostedWorld)session.world();

            client.chat("/world fortress");
            client.next(Packets.WORLD_DATA_BEGIN);
            TestClient.WorldData world = client.world();
            assertEquals("Fortress", world.tags().get("name"));
            assertEquals("fortress", session.world().name());
            assertEquals(SessionState.LOADING, session.state());
            assertEquals(2, session.routeGeneration());
            client.confirm();
            await("alpha to play in fortress", () -> session.state() == SessionState.IN_WORLD);
            assertEquals(0, maze.inspect(w -> Roster.of(w).size()).get(), "the source removed its player");
            assertNull(maze.inspect(w -> Roster.of(w).player(session.id())).get());
            assertNotNull(((HostedWorld)session.world()).inspect(w -> Roster.of(w).player(session.id())).get());
            assertFalse(client.receives(Packets.SEND_MESSAGE, 200), "no second greeting");

            client.chat("/world 0");
            client.next(Packets.WORLD_DATA_BEGIN);
            assertEquals("Maze", client.world().tags().get("name"));
        }
    }

    // ---- kicks: each connect check, in vanilla's order (NetServer.java:158-300)

    /** Connects with {@code connect} and returns the kick reason the server answers with; it then closes. */
    static KickReason kicked(TestClient.Connect connect) throws Exception{
        try(var client = client()){
            client.connect(connect.packet());
            int reason = client.kickReason();
            assertTrue(client.closedWithin(Hang.MILLIS), "closed after the kick");
            return KickReason.of(reason);
        }
    }

    @Test
    void aBannedAddressIsIgnored() throws Exception{
        server.admin().ban(Bans.Kind.ADDRESS, "127.0.0.1", "test", null, "test").join();
        try(var client = client()){
            client.connect("alpha");
            assertTrue(client.closedWithin(Hang.MILLIS));
            assertTrue(client.received.entrySet().stream().allMatch(e -> e.getKey() == (Frame.FRAMEWORK & 0xff) || e.getValue().isEmpty()), "no answer");
        }finally{
            server.admin().unban(Bans.Kind.ADDRESS, "127.0.0.1", "test").join();
        }
    }

    @Test
    void tooManyUuidsFromOneAddressBanIt() throws Exception{
        set(ConfigKeys.ADMIN_UUID_CHANGE_LIMIT, "2");
        try{
            for(String name : List.of("one", "two")){
                try(var client = client()){
                    client.connect(name);
                    client.world();
                }
                await(name + " to leave", () -> server.sessions().size() == 0);
            }
            assertEquals(KickReason.banned, kicked(TestClient.Connect.named("three")));
            await("the address ban", () -> server.admin().index().addressBan(InetAddress.ofLiteral("127.0.0.1"), System.currentTimeMillis()).isPresent());
        }finally{
            server.admin().unban(Bans.Kind.ADDRESS, "127.0.0.1", "test").join();
            // a ban has vanilla's 30 s cooldown, on the address too
            server.admin().pardon(TestClient.Connect.named("three").packet().uuidString());
        }
    }

    @Test
    void aSecondConnectPacketIsAnIdInUse() throws Exception{
        try(var client = client()){
            client.connect("alpha");
            client.world();
            client.connect("alpha");
            assertEquals(KickReason.idInUse, KickReason.of(client.kickReason()));
            assertTrue(client.closedWithin(Hang.MILLIS));
        }
    }

    @Test
    void aMissingUsidIsAnIdInUse() throws Exception{
        assertEquals(KickReason.idInUse, kicked(TestClient.Connect.named("alpha").usid(null)));
    }

    @Test
    void aBannedUuidIsBanned() throws Exception{
        var connect = TestClient.Connect.named("alpha");
        server.admin().ban(Bans.Kind.UUID, connect.packet().uuidString(), "test", null, "test").join();
        try{
            assertEquals(KickReason.banned, kicked(connect));
        }finally{
            server.admin().unban(Bans.Kind.UUID, connect.packet().uuidString(), "test").join();
            server.admin().pardon(connect.packet().uuidString());
        }
    }

    @Test
    void aConsoleKickHasACooldown() throws Exception{
        var out = new ByteArrayOutputStream();
        var console = new Console(server, new PrintStream(out, true, StandardCharsets.UTF_8), () -> {});
        var connect = TestClient.Connect.named("alpha");
        try(var client = client()){
            join(client, "alpha");
            console.run("kick alpha");
            assertEquals(KickReason.kick, KickReason.of(client.kickReason()));
            assertTrue(client.closedWithin(Hang.MILLIS));
        }
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("It is done."), out.toString(StandardCharsets.UTF_8));
        try{
            await("the leave", () -> server.sessions().size() == 0);
            assertEquals(KickReason.recentKick, kicked(connect));
            // the cooldown holds the address too, as vanilla's does
            assertEquals(KickReason.recentKick, kicked(TestClient.Connect.named("other")));
        }finally{
            assertTrue(server.admin().pardon(connect.packet().uuidString()));
        }
    }

    @Test
    void aConsoleBanKicksAndKeepsThemOut() throws Exception{
        var out = new ByteArrayOutputStream();
        var console = new Console(server, new PrintStream(out, true, StandardCharsets.UTF_8), () -> {});
        var connect = TestClient.Connect.named("alpha");
        String uuid = connect.packet().uuidString();
        try{
            try(var client = client()){
                join(client, "alpha");
                console.run("ban id " + uuid);
                assertEquals(KickReason.banned, KickReason.of(client.kickReason()));
                assertTrue(client.closedWithin(Hang.MILLIS));
            }
            assertTrue(out.toString(StandardCharsets.UTF_8).contains("Banned."), out.toString(StandardCharsets.UTF_8));
            await("the leave", () -> server.sessions().size() == 0);
            assertTrue(server.admin().pardon(uuid), "the ban's kick has a cooldown");
            assertEquals(KickReason.banned, kicked(connect));
        }finally{
            server.admin().unban(Bans.Kind.UUID, uuid, "test").join();
            server.admin().pardon(uuid);
        }
    }

    @Test
    void aFullHostIsAPlayerLimit() throws Exception{
        set(ConfigKeys.NET_PLAYER_LIMIT, "1");
        try(var client = client()){
            join(client, "alpha");
            assertEquals(KickReason.playerLimit, kicked(TestClient.Connect.named("beta")));
        }
    }

    @Test
    void aStrangerOnAWhitelistIsToldSo() throws Exception{
        set(ConfigKeys.ADMIN_WHITELIST, "true");
        try(var client = client()){
            client.connect("alpha");
            assertEquals("You are not whitelisted here.", string(client.payload(Packets.INFO_MESSAGE)));
            assertEquals(KickReason.whitelist, KickReason.of(client.kickReason()));
            assertTrue(client.closedWithin(Hang.MILLIS));
        }
    }

    @Test
    void modsAreUnnecessary() throws Exception{
        try(var client = client()){
            client.connect(TestClient.Connect.named("alpha").mods(List.of("example-mod", "other")).packet());
            assertEquals("[accent]Incompatible mods![]\n\nUnnecessary mods:[lightgray]\n> example-mod\n> other", string(client.payload(Packets.KICK)));
            assertTrue(client.closedWithin(Hang.MILLIS));
        }
    }

    @Test
    void otherVersionTypesAndCustomBuildsAreRefused() throws Exception{
        assertEquals(KickReason.typeMismatch, kicked(TestClient.Connect.named("alpha").type("bleeding-edge")));
        assertEquals(KickReason.typeMismatch, kicked(TestClient.Connect.named("alpha").type(null)));
        assertEquals(KickReason.customClient, kicked(TestClient.Connect.named("alpha").build(-1)));
    }

    @Test
    void customBuildsJoinWhenAllowed() throws Exception{
        set(ConfigKeys.NET_ALLOW_CUSTOM_CLIENTS, "true");
        try(var client = client()){
            client.connect(TestClient.Connect.named("alpha").build(-1).type("foo's client").packet());
            assertEquals("Maze", client.world().tags().get("name"));
        }
    }

    @Test
    void strictModeRefusesDuplicateNamesAndIds() throws Exception{
        try(var client = client()){
            join(client, "alpha");
            assertEquals(KickReason.nameInUse, kicked(TestClient.Connect.named("[red]ALPHA ")));
            assertEquals(KickReason.idInUse, kicked(TestClient.Connect.named("beta").id(TestClient.id("alpha"))));
            assertEquals(KickReason.idInUse, kicked(TestClient.Connect.named("gamma").usid("usid-alpha")));
        }
    }

    @Test
    void duplicatesJoinWithoutStrictMode() throws Exception{
        set(ConfigKeys.GAME_STRICT, "false");
        try(var alpha = client(); var again = client()){
            join(alpha, "alpha");
            again.connect(TestClient.Connect.named("alpha").packet());
            assertEquals("Maze", again.world().tags().get("name"));
        }
    }

    @Test
    void anEmptyNameIsNameEmpty() throws Exception{
        assertEquals(KickReason.nameEmpty, kicked(TestClient.Connect.named("   ")));
        assertEquals(KickReason.nameEmpty, kicked(TestClient.Connect.named("")));
        // vanilla checks the fixed name's length, colors and all: "[red]" is a name
        try(var client = client()){
            client.connect("[red]  ");
            client.world();
        }
    }

    @Test
    void otherBuildsAreOutdated() throws Exception{
        assertEquals(KickReason.clientOutdated, kicked(TestClient.Connect.named("alpha").build(146)));
        assertEquals(KickReason.serverOutdated, kicked(TestClient.Connect.named("alpha").build(161)));
    }

    @Test
    void aMalformedConnectPacketCloses() throws Exception{
        long before = server.flow().malformed.sum();
        try(var client = client()){
            client.tcp(Packets.CONNECT_PACKET, new byte[]{0, 0, 0, (byte)160, 0});
            assertTrue(client.closedWithin(Hang.MILLIS));
        }
        assertEquals(before + 1, server.flow().malformed.sum());
    }

    @Test
    void discoveryDescribesTheHost() throws Exception{
        server.refreshDiscovery();
        try(var socket = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))){
            socket.setSoTimeout((int)Hang.MILLIS);
            socket.send(new DatagramPacket(new byte[]{Frame.FRAMEWORK, Frame.DISCOVER_HOST}, 2,
                server.transport().address()));
            var reply = new DatagramPacket(new byte[512], 512);
            socket.receive(reply);
            var data = ByteBuffer.wrap(reply.getData(), 0, reply.getLength());
            assertEquals(server.config().current().get(ConfigKeys.SERVER_NAME), shortString(data));
            assertEquals("Maze", shortString(data));
            assertEquals(0, data.getInt(), "players");
            data.getInt();
            assertEquals(160, data.getInt());
            assertEquals("official", shortString(data));
        }
    }

    static String shortString(ByteBuffer data){
        byte[] bytes = new byte[data.get() & 0xff];
        data.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Test
    void theConsoleInspectsTilesAndPlayers() throws Exception{
        var out = new ByteArrayOutputStream();
        var console = new Console(server, new PrintStream(out, true, StandardCharsets.UTF_8), () -> {});
        try(var client = client()){
            join(client, "alpha");
            console.run("players");
            console.run("tile maze 0 0");
            console.run("status");
        }
        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains(" alpha uuid=") && text.contains(" world=maze state=in_world"), text);
        assertTrue(text.contains("Players: 1"), text);
        assertTrue(text.lines().anyMatch(line -> line.startsWith("tile 0,0 floor=")), text);
        assertTrue(text.contains("Status: 2 worlds"), text);
    }

    @Test
    void theConsoleSaysSuggestsAndVersions() throws Exception{
        var out = new ByteArrayOutputStream();
        var console = new Console(server, new PrintStream(out, true, StandardCharsets.UTF_8), () -> {});
        try(var client = client()){
            join(client, "alpha");
            console.run("sya hello all");
            console.run("yes");
            client.message("[scarlet][[Server]:[] hello all");
        }
        console.run("yes");
        console.run("frobnicate");
        console.run("version");
        String text = out.toString(StandardCharsets.UTF_8);
        for(String line : List.of("Command not found. Did you mean \"say\"?", "Server: hello all", "There is nothing to say yes to.",
            "Invalid command. Type 'help' for help.", "Version: Mindustry 8-release official / build 160.7 (Shard ")){
            assertTrue(text.contains(line), line + " in:\n" + text);
        }
    }

    @Test
    void perfReportsWindowsAndASummary() throws Exception{
        var out = new ByteArrayOutputStream();
        var console = new Console(server, new PrintStream(out, true, StandardCharsets.UTF_8), () -> {});
        console.run("perf summary");
        console.run("perf mark");
        try(var client = client()){
            join(client, "alpha");
            // ticks since the mark, for its lateness
            long ticks = ticks(server);
            await("a few ticks", () -> ticks(server) >= ticks + 3);
            console.run("perf");
        }
        console.run("perf summary");
        List<String> lines = out.toString(StandardCharsets.UTF_8).lines().toList();
        assertEquals("perf: no perf mark yet", lines.get(0));
        assertTrue(lines.get(1).startsWith("perf mark at t="), lines.get(1));
        assertTrue(lines.get(2).matches("perf t=\\d+s worlds=2 players=1 sessions=1 .* late\\[n=[1-9]\\d* .*dropped=\\d+ .*"), lines.get(2));
        assertTrue(lines.get(3).matches("SUMMARY seconds=\\d+ worlds=2 players=\\d+ cpu=.* late\\[n=[1-9]\\d* .*"), lines.get(3));
    }
}
