package com.xpdustry.shard.server.admin;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.wire.Packet;
import com.xpdustry.shard.server.*;
import com.xpdustry.shard.server.console.Console;
import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.server.session.*;
import com.xpdustry.shard.server.store.Bans;
import com.xpdustry.shard.sim.waves.*;
import com.xpdustry.shard.wire.Codecs;
import com.xpdustry.shard.wire.WireObjects;
import com.xpdustry.shard.wire.v160_7.AdminAction;
import com.xpdustry.shard.wire.v160_7.Packets;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static com.xpdustry.shard.server.ServerTest.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * An admin's {@code adminRequest} from a byte-level client, end to end (NetServer.java:906): kick, ban, trace, wave
 * skip and team switch, with the store's state after each, and vanilla's two refusals (not an admin; an admin as the
 * target). The admin is made one by the console's {@code admin add}. Each player has its own loopback address, so a
 * kick's address cooldown or an address ban stays on its target.
 */
class AdminRequestServerTest{
    @TempDir
    static Path temp;
    static Server server;
    static Console console;
    static final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final List<TestClient> clients = new ArrayList<>();

    @BeforeAll
    static void start() throws Exception{
        server = Server.start(new Server.Options(ServerTest.configure(temp.resolve("main"), """
            host:
              worlds: [maze]
            chat:
              show-connect-messages: false
            """), Map.of(), true));
        console = new Console(server, new PrintStream(out, true, StandardCharsets.UTF_8), () -> {});
    }

    @AfterAll
    static void stop(){
        if(server != null) server.close();
    }

    @AfterEach
    void settle() throws Exception{
        for(TestClient client : clients) client.close();
        await("the sessions to close", () -> server.sessions().size() == 0);
        for(var ban : server.admin().activeBans().join()) server.admin().revoke(ban.id(), "test").join();
        for(var grant : server.admin().admins().join()) server.admin().revokeAdmin(grant.player().uuid()).join();
        server.admin().pardonAll();
    }

    TestClient join(String name, String local) throws Exception{
        var client = new TestClient(server.transport().address(), InetAddress.getByName(local));
        clients.add(client);
        client.connect(name);
        client.world();
        client.confirm();
        await(name + " to play", () -> session(name) != null && session(name).state() == SessionState.IN_WORLD && player(name) >= 0);
        return client;
    }

    /** {@code alpha} at 127.0.0.2, made an admin by the console, and {@code beta} at 127.0.0.3. */
    TestClient[] adminAndTarget() throws Exception{
        TestClient alpha = join("alpha", "127.0.0.2"), beta = join("beta", "127.0.0.3");
        await("the joins to be recorded", () -> server.admin().player(uuid("alpha")).join().isPresent() && server.admin().player(uuid("beta")).join().isPresent());
        console.run("admin add alpha");
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("Changed admin status of player: alpha"), out.toString(StandardCharsets.UTF_8));
        await("alpha to be an admin", () -> session("alpha").admin() && world().inspect(w -> Roster.of(w).player(session("alpha").id()).admin).join());
        return new TestClient[]{alpha, beta};
    }

    static Session session(String name){
        return server.sessions().all().stream().filter(s -> s.identity().plainName().equals(name)).findFirst().orElse(null);
    }

    static HostedWorld world(){
        return server.host().world("maze").orElseThrow();
    }

    /** The player id of {@code name} in maze, or -1 until the world added it. */
    static int player(String name){
        long id = session(name).id();
        return world().inspect(w -> {
            var player = Roster.of(w).player(id);
            return player == null || !player.isAdded() ? -1 : player.id();
        }).orTimeout(Hang.SECONDS, TimeUnit.SECONDS).join();
    }

    static String uuid(String name){
        return TestClient.Connect.named(name).packet().uuidString();
    }

    static void send(TestClient client, Packet packet) throws Exception{
        var write = new Writes(32);
        packet.write(write, false, null);
        client.tcp(packet.packetId(), write.toByteArray());
    }

    static void request(TestClient by, String other, AdminAction action, Object params) throws Exception{
        send(by, new Packets.AdminRequest(player(other), action, params));
    }

    @Test
    void kickKicksWithACooldownAndCountsIt() throws Exception{
        var pair = adminAndTarget();
        int before = server.admin().kicks(uuid("beta")).join().size();
        request(pair[0], "beta", AdminAction.kick, null);
        assertEquals(KickReason.KICK.id(), pair[1].kickReason());
        assertTrue(pair[1].closedWithin(Hang.MILLIS));

        await("the kick to be stored", () -> server.admin().kicks(uuid("beta")).join().size() == before + 1);
        var kick = server.admin().kicks(uuid("beta")).join().getLast();
        assertEquals("kick", kick.reason());
        assertEquals("player:" + uuid("alpha"), kick.createdBy());
        assertEquals("127.0.0.3", kick.address());
        assertEquals(30_000, kick.expiresAt() - kick.createdAt(), "admin.kick-cooldown, vanilla's 30 s");
        assertEquals(before + 1, server.admin().player(uuid("beta")).join().orElseThrow().player().timesKicked());
        assertTrue(server.admin().kickedUntil(uuid("beta"), "127.0.0.3") > System.currentTimeMillis());
        assertTrue(server.admin().activeBans().join().isEmpty(), "a kick bans nothing");
    }

    @Test
    void banBansTheUuidAndTheLiveAddressAndKicks() throws Exception{
        var pair = adminAndTarget();
        int before = server.admin().kicks(uuid("beta")).join().size();
        request(pair[0], "beta", AdminAction.ban, null);
        assertEquals(KickReason.BANNED.id(), pair[1].kickReason());
        assertTrue(pair[1].closedWithin(Hang.MILLIS));

        await("the bans", () -> server.admin().activeBans().join().size() == 2);
        var bans = server.admin().activeBans().join();
        long beta = server.admin().player(uuid("beta")).join().orElseThrow().player().id();
        assertEquals(List.of(Bans.Kind.UUID, Bans.Kind.ADDRESS), bans.stream().map(Bans.Ban::kind).toList());
        assertEquals(List.of(uuid("beta"), "127.0.0.3"), bans.stream().map(Bans.Ban::target).toList());
        for(var ban : bans){
            assertEquals(beta, ban.playerId());
            assertEquals("player:" + uuid("alpha"), ban.createdBy());
            assertNull(ban.expiresAt(), "permanent, as vanilla's");
        }
        await("the ban's kick", () -> server.admin().kicks(uuid("beta")).join().size() == before + 1);
        assertEquals("banned", server.admin().kicks(uuid("beta")).join().getLast().reason());
        assertEquals(before + 1, server.admin().player(uuid("beta")).join().orElseThrow().player().timesKicked(), "kick(banned) counts, as in vanilla");
        assertFalse(server.admin().admit(new AdminService.Connect(uuid("beta"), "u", "beta", "127.0.0.9", 0)) instanceof AdminService.Decision.Admit);
        assertInstanceOf(AdminService.Decision.Ignore.class, server.admin().admit(new AdminService.Connect("other", "u", "other", "127.0.0.3", 0)));
        assertInstanceOf(AdminService.Decision.Admit.class, server.admin().admit(new AdminService.Connect(uuid("alpha"), "u", "alpha", "127.0.0.2", 0)),
            "alpha's own address is free: an address ban isn't every address's history");

        console.run("unban 127.0.0.3");
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("Unbanned player: 127.0.0.3"));
        assertTrue(server.admin().activeBans().join().isEmpty(), "unbanning the address frees the player completely, as vanilla's");
    }

    @Test
    void traceAnswersWithTheTargetsHistory() throws Exception{
        var pair = adminAndTarget();
        request(pair[0], "beta", AdminAction.trace, null);
        var read = new Reads(pair[0].payload(Packets.TraceInfo.ID));
        assertEquals(player("beta"), read.i());
        var info = Codecs.readTraceInfo(read);
        assertEquals("127.0.0.3", info.ip());
        assertEquals(uuid("beta"), info.uuid());
        assertEquals(session("beta").identity().locale(), info.locale());
        assertFalse(info.modded());
        assertFalse(info.mobile());
        var stored = server.admin().player(uuid("beta")).join().orElseThrow().player();
        assertEquals(stored.timesJoined(), info.timesJoined(), "every test joins beta again");
        assertEquals(stored.timesKicked(), info.timesKicked());
        assertEquals(List.of("127.0.0.3"), info.ips());
        assertEquals(List.of("beta"), info.names());
        assertFalse(pair[1].receives(Packets.TraceInfo.ID, 0), "only the admin hears it");
    }

    @Test
    void waveSkipsTheWaveInTheWorld() throws Exception{
        var pair = adminAndTarget();
        int before = world().inspect(w -> WaveState.of(w).wave).join();
        request(pair[0], "alpha", AdminAction.wave, null);
        await("the next wave", () -> world().inspect(w -> WaveState.of(w).wave).join() == before + 1);
        request(pair[0], "alpha", AdminAction.wave, null);
        await("another, unchecked as in vanilla", () -> world().inspect(w -> WaveState.of(w).wave).join() == before + 2);
    }

    @Test
    void switchTeamMovesTheTarget() throws Exception{
        var pair = adminAndTarget();
        long beta = session("beta").id();
        request(pair[0], "beta", AdminAction.switchTeam, new WireObjects.TeamRef(2));
        await("beta on crux", () -> world().inspect(w -> {
            var player = Roster.of(w).player(beta);
            return player == null ? -1 : player.team;
        }).join() == 2);
    }

    @Test
    void aPlayerWhoIsntAnAdminIsRefused() throws Exception{
        TestClient alpha = join("alpha", "127.0.0.2"), beta = join("beta", "127.0.0.3");
        int before = world().inspect(w -> WaveState.of(w).wave).join();
        request(beta, "alpha", AdminAction.kick, null);
        request(beta, "beta", AdminAction.wave, null);
        assertFalse(alpha.closedWithin(1000), "ACCESS DENIED");
        assertEquals(before, world().inspect(w -> WaveState.of(w).wave).join());
        assertTrue(server.admin().kicks(uuid("alpha")).join().isEmpty());
    }

    @Test
    void anAdminCantActOnAnotherAdmin() throws Exception{
        var pair = adminAndTarget();
        console.run("admin add beta");
        await("beta to be an admin", () -> world().inspect(w -> Roster.of(w).player(session("beta").id()).admin).join());
        request(pair[0], "beta", AdminAction.kick, null);
        assertFalse(pair[1].closedWithin(1000));
        request(pair[0], "beta", AdminAction.ban, null);
        assertFalse(pair[1].closedWithin(1000));
        assertTrue(server.admin().activeBans().join().isEmpty());

        console.run("admin remove beta");
        await("beta not to be an admin", () -> !world().inspect(w -> Roster.of(w).player(session("beta").id()).admin).join());
        assertFalse(session("beta").admin());
        assertEquals(List.of(uuid("alpha")), server.admin().admins().join().stream().map(g -> g.player().uuid()).toList());
    }
}
