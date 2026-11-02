package com.xpdustry.shard.server.ui;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.net.Frame;
import com.xpdustry.shard.server.*;
import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.server.session.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.Codecs;
import com.xpdustry.shard.wire.Packet;
import com.xpdustry.shard.wire.v160_7.Packets;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static com.xpdustry.shard.server.ServerTest.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The plugin UI end to end with byte-level clients in two worlds: each {@link To} reaches exactly its players, a
 * loading player gets its frames once it confirms, and menu choices, text input and plugin packets come back to
 * their listeners on the world's thread with the answering player.
 */
class UiServerTest{
    @TempDir
    static Path temp;
    static Server server;
    final List<TestClient> clients = new ArrayList<>();

    @BeforeAll
    static void start() throws Exception{
        server = Server.start(new Server.Options(ServerTest.configure(temp.resolve("main"), """
            host:
              worlds: [maze, fortress]
              join: first
            chat:
              show-connect-messages: false
            """), Map.of(), true));
    }

    @AfterAll
    static void stop(){
        if(server != null) server.close();
    }

    @AfterEach
    void settle() throws Exception{
        for(TestClient client : clients) client.close();
        await("the sessions to close", () -> server.sessions().size() == 0);
    }

    static Session session(String name){
        return server.sessions().all().stream().filter(s -> s.identity().plainName().equals(name)).findFirst().orElse(null);
    }

    static boolean added(Session session){
        if(!(session.world() instanceof HostedWorld world)) return false;
        var player = world.inspect(w -> Roster.of(w).player(session.id())).orTimeout(Hang.SECONDS, TimeUnit.SECONDS).join();
        return player != null && player.isAdded();
    }

    TestClient join(String name, String world) throws Exception{
        var client = new TestClient(server.transport().address());
        clients.add(client);
        client.connect(name);
        client.loads(1);
        client.confirm();
        await(name + " to play", () -> session(name) != null && session(name).state() == SessionState.IN_WORLD && added(session(name)));
        if(!world.equals("maze")){
            client.chat("/world " + world);
            await(name + " to load " + world, () -> session(name).state() == SessionState.LOADING);
            client.loads(3);
            client.confirm();
            await(name + " to play in " + world, () -> session(name).state() == SessionState.IN_WORLD && added(session(name)));
        }
        return client;
    }

    static HostedWorld world(String id){
        return server.host().world(id).orElseThrow();
    }

    /** Sends a client-to-server Call's body. */
    static void send(TestClient client, Packet packet) throws Exception{
        var out = new Writes(32);
        packet.write(out, false, null);
        client.tcp(packet.packetId(), out.toByteArray());
    }

    static String announce(TestClient client, long millis) throws InterruptedException{
        byte[] body = client.queue(Packets.Announce.ID).poll(millis, TimeUnit.MILLISECONDS);
        return body == null ? null : Codecs.readString(new Reads(Frame.decodePayload(body, 1, body.length - 1)));
    }

    @Test
    void eachTargetReachesItsPlayers() throws Exception{
        TestClient alpha = join("alpha", "maze"), beta = join("beta", "maze"), gamma = join("gamma", "fortress");
        HostedWorld maze = world("maze");
        // beta on crux, through a logged admin command
        long betaId = session("beta").id();
        assertTrue(maze.managed().offer(new Submission(new SetTeam(betaId, 2), new Cause.Admin("test"))).get(Hang.SECONDS, TimeUnit.SECONDS));
        await("beta on crux", () -> maze.inspect(w -> Roster.of(w).player(betaId).team).orTimeout(Hang.SECONDS, TimeUnit.SECONDS).join() == 2);

        Ui ui = server.ui();
        ui.announce(To.player(session("alpha")), "only alpha");
        assertEquals("only alpha", announce(alpha, 5_000));
        assertNull(announce(beta, 300));
        assertNull(announce(gamma, 300));

        ui.announce(To.team(maze, 2), "crux");
        assertEquals("crux", announce(beta, 5_000));
        assertNull(announce(alpha, 300));

        ui.announce(To.world(maze), "maze");
        assertEquals("maze", announce(alpha, 5_000));
        assertEquals("maze", announce(beta, 5_000));
        assertNull(announce(gamma, 300));

        ui.announce(To.everyone(), "all");
        for(TestClient client : List.of(alpha, beta, gamma)) assertEquals("all", announce(client, 5_000));
    }

    record SetTeam(long session, int team) implements Command.Admin{
        @Override
        public Verdict apply(World world, Cause cause){
            Roster.of(world).player(session).team(team);
            return Verdict.ADMITTED;
        }
    }

    @Test
    void aLoadingPlayerGetsItOnceItConfirms() throws Exception{
        var client = new TestClient(server.transport().address());
        clients.add(client);
        client.connect("loading");
        // its stream is captured: from here on its world holds its frames until the confirm
        assertEquals(List.of("load Maze"), client.loads(1));
        server.ui().announce(To.player(session("loading")), "welcome");
        assertNull(announce(client, 300), "held while it loads");
        client.confirm();
        assertEquals("welcome", announce(client, 5_000));
    }

    @Test
    void answersComeBackToTheirListeners() throws Exception{
        TestClient alpha = join("alpha", "maze");
        Ui ui = server.ui();
        var answers = new LinkedBlockingQueue<Answer>();
        int menu = ui.registerMenu(answers::add), input = ui.registerTextInput(answers::add);
        ui.menu(To.player(session("alpha")), menu, "Title", "Pick", new String[][]{{"a", "b"}});
        alpha.payload(Packets.Menu.ID);

        send(alpha, new Packets.MenuChoose(-1, menu, 1));
        Answer chose = answers.poll(Hang.SECONDS, TimeUnit.SECONDS);
        assertNotNull(chose);
        assertEquals(menu, chose.id());
        assertEquals(1, chose.option());
        assertSame(session("alpha"), chose.session());
        assertSame(world("maze"), chose.world());
        assertEquals("alpha", chose.player().name);

        send(alpha, new Packets.TextInputResult(-1, input, "typed"));
        Answer typed = answers.poll(Hang.SECONDS, TimeUnit.SECONDS);
        assertEquals(input, typed.id());
        assertEquals("typed", typed.text());

        var packets = new LinkedBlockingQueue<PluginPacket>();
        ui.onPacket("plugin:ping", packets::add);
        send(alpha, new Packets.ServerPacketReliable("plugin:ping", "payload"));
        send(alpha, new Packets.ServerPacketReliable("plugin:other", "ignored"));
        PluginPacket ping = packets.poll(Hang.SECONDS, TimeUnit.SECONDS);
        assertEquals("payload", new String(ping.contents(), StandardCharsets.UTF_8));
        assertEquals(PluginPacket.Kind.TEXT, ping.kind());
        assertTrue(ping.reliable());
        assertNull(packets.poll(300, TimeUnit.MILLISECONDS));

        // a listener that throws doesn't stop the world
        int broken = ui.registerMenu(answer -> {
            throw new IllegalStateException("plugin bug");
        });
        send(alpha, new Packets.MenuChoose(-1, broken, 0));
        send(alpha, new Packets.MenuChoose(-1, menu, -1));
        assertEquals(-1, answers.poll(Hang.SECONDS, TimeUnit.SECONDS).option());
    }
}
