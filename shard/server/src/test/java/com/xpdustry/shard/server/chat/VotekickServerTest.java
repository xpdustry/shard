package com.xpdustry.shard.server.chat;

import com.xpdustry.shard.server.*;
import com.xpdustry.shard.server.admin.*;
import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.server.session.*;
import com.xpdustry.shard.server.sched.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.lang.ref.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static com.xpdustry.shard.server.ServerTest.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Vanilla's votekick with three byte-level clients on three loopback addresses (vanilla counts a vote by uuid and by
 * address): three players are needed and a reason, the starter's vote counts, two votes of three players pass and
 * kick the target with {@code vote} and an hour's ban, a starter waits five minutes between votes, and a vote that
 * doesn't pass within 30 s fails. Time is the chat's {@link ManualClock}.
 */
class VotekickServerTest{
    @TempDir
    static Path temp;
    static Server server;
    /** One clock for the class: the votes and cooldowns are the server's, and outlive a test, as vanilla's do. */
    static final ManualClock clock = new ManualClock();
    final List<TestClient> clients = new ArrayList<>();

    @BeforeAll
    static void start() throws Exception{
        server = Server.start(new Server.Options(ServerTest.configure(temp.resolve("main"), """
            host:
              worlds: [maze]
            chat:
              show-connect-messages: false
            """), Map.of(), true));
    }

    @AfterAll
    static void stop(){
        if(server != null) server.close();
    }

    @BeforeEach
    void clock(){
        server.chat().clock(clock);
        // past any cooldown, and past a vote an earlier test left open
        clock.advance(Votekick.COOLDOWN.toNanos() + Votekick.DURATION.toNanos() + 1);
    }

    @AfterEach
    void settle() throws Exception{
        for(TestClient client : clients) client.close();
        await("the sessions to close", () -> server.sessions().size() == 0);
        server.chat().clock(NanoClock.SYSTEM);
        server.admin().pardonAll();
    }

    TestClient join(String name, String address) throws Exception{
        return Received.join(server, clients, name, address);
    }

    int player(String name){
        return Received.player(Received.session(server, name));
    }

    /** Lines each client heard that {@link #heard} hasn't matched yet. */
    final Map<TestClient, ArrayDeque<String>> backlog = new HashMap<>();

    /** The next server line {@code client} hears that starts with {@code prefix}, skipping the lines before it. */
    String heard(TestClient client, String prefix) throws InterruptedException{
        ArrayDeque<String> lines = backlog.computeIfAbsent(client, c -> new ArrayDeque<>());
        var seen = new ArrayList<String>();
        long deadline = System.nanoTime() + Hang.NANOS;
        while(System.nanoTime() < deadline){
            if(lines.isEmpty()) lines.addAll(Received.drain(client, 50));
            for(String line; (line = lines.poll()) != null; ){
                if(line.startsWith(prefix)) return line;
                seen.add(line);
            }
        }
        return fail("no line starting '" + prefix + "' within " + Hang.SECONDS + " s; got " + seen);
    }

    static String voted(String voter, String target, int votes){
        return "[lightgray]" + voter + "[lightgray] has voted on kicking[orange] " + target + "[lightgray].[accent] (" + votes
            + "/2)\n[lightgray]Type[orange] /vote <y/n>[] to agree.";
    }

    @Test
    void twoOfThreeVotesKickForAnHour() throws Exception{
        TestClient alpha = join("alpha", "127.0.0.1"), beta = join("beta", "127.0.0.2");
        alpha.chat("/votekick beta griefing");
        assertEquals(List.of("[scarlet]At least 3 players are needed to start a votekick."), Received.messages(alpha, 1));

        TestClient gamma = join("gamma", "127.0.0.3");
        alpha.chat("/votekick");
        assertEquals(List.of("[orange]Players to kick: \n[lightgray] beta[accent] (#" + player("beta") + ")\n[lightgray] gamma[accent] (#" + player("gamma") + ")\n"),
            Received.messages(alpha, 1));
        alpha.chat("/votekick gamma");
        assertEquals(List.of("[orange]You need a valid reason to kick the player. Add a reason after the player name."), Received.messages(alpha, 1));
        alpha.chat("/votekick alpha me");
        assertEquals(List.of("[scarlet]You can't vote to kick yourself."), Received.messages(alpha, 1));
        alpha.chat("/votekick nobody me");
        assertEquals(List.of("[scarlet]No player [orange]'nobody'[scarlet] found."), Received.messages(alpha, 1));
        beta.chat("/vote y");
        assertEquals(List.of("[scarlet]Nobody is being voted on."), Received.messages(beta, 1));

        alpha.chat("/votekick #" + player("gamma") + " griefing the core");
        for(TestClient client : List.of(alpha, beta, gamma)){
            assertEquals(List.of(voted("alpha", "gamma", 1), "[lightgray]Reason:[orange] griefing the core[lightgray]."), Received.messages(client, 2));
        }
        beta.chat("/votekick alpha x");
        assertEquals(List.of("[scarlet]A vote is already in progress."), Received.messages(beta, 1));
        gamma.chat("/vote y");
        assertEquals(List.of("[scarlet]You can't vote on your own trial."), Received.messages(gamma, 1));
        alpha.chat("/vote y");
        assertEquals(List.of("[scarlet]You've already voted y. Sit down."), Received.messages(alpha, 1));
        beta.chat("/vote maybe");
        assertEquals(List.of("[scarlet]Vote either 'y' (yes) or 'n' (no)."), Received.messages(beta, 1));

        beta.chat("/vote y");
        assertEquals(voted("beta", "gamma", 2), heard(alpha, "[lightgray]beta"));
        assertEquals("[orange]Vote passed.[scarlet] gamma[orange] will be banned from the server for 60 minutes.", heard(alpha, "[orange]Vote passed."));
        assertEquals(KickReason.VOTE.id(), gamma.kickReason());
        assertTrue(gamma.closedWithin(Hang.MILLIS));
        String uuid = TestClient.Connect.named("gamma").packet().uuidString();
        long until = server.admin().kickedUntil(uuid, "127.0.0.3") - System.currentTimeMillis();
        assertTrue(until > 3_500_000 && until <= Votekick.BAN.toMillis(), "an hour's ban: " + until);
        assertFalse(alpha.closedWithin(200) || beta.closedWithin(200), "the voters stay");

        // gamma can't come back
        var again = new TestClient(server.transport().address(), java.net.InetAddress.getByName("127.0.0.3"));
        clients.add(again);
        again.connect("gamma");
        assertEquals(KickReason.RECENT_KICK.id(), again.kickReason());
    }

    @Test
    void votesCountByAddressToo() throws Exception{
        // a quirk kept: beta shares alpha's address, so alpha's yes counts as beta's (NetServer.java:486)
        TestClient alpha = join("alpha", "127.0.0.1"), beta = join("beta", "127.0.0.1");
        join("gamma", "127.0.0.3");
        alpha.chat("/votekick gamma griefing");
        heard(beta, "[lightgray]Reason:");
        beta.chat("/vote y");
        assertEquals(List.of("[scarlet]You've already voted y. Sit down."), Received.messages(beta, 1));
        beta.chat("/vote n");
        // its earlier votes by uuid (none) and by address (alpha's 1) are OR-ed and taken back: 1 - 1 - 1
        assertEquals(voted("beta", "gamma", -1), heard(alpha, "[lightgray]beta"));
    }

    @Test
    void aVoteFailsAfterThirtySecondsAndStartersCoolDown() throws Exception{
        TestClient alpha = join("alpha", "127.0.0.1"), beta = join("beta", "127.0.0.2");
        join("gamma", "127.0.0.3");
        alpha.chat("/votekick gamma afk");
        heard(beta, "[lightgray]Reason:");
        clock.advance(Votekick.DURATION.toNanos() - 1);
        assertTrue(Received.quiet(beta), "still open");
        clock.advance(1);
        assertEquals("[lightgray]Vote failed. Not enough votes to kick[orange] gamma[lightgray].", heard(beta, "[lightgray]Vote failed"));

        heard(alpha, "[lightgray]Vote failed");
        alpha.chat("/votekick gamma afk");
        assertEquals(List.of("[scarlet]You must wait 5 minutes between votekicks."), Received.messages(alpha, 1));
        clock.advance(Votekick.COOLDOWN.toNanos() - Votekick.DURATION.toNanos());
        alpha.chat("/votekick gamma afk");
        assertEquals(List.of("[scarlet]You must wait 5 minutes between votekicks."), Received.messages(alpha, 1), "Timekeeper: more than the interval");
        // another starter has no cooldown of its own
        beta.chat("/votekick gamma afk");
        assertEquals(voted("beta", "gamma", 1), heard(alpha, "[lightgray]beta"));
        clock.advance(1);
        alpha.chat("/votekick gamma afk");
        assertEquals(List.of("[scarlet]A vote is already in progress."), Received.messages(alpha, 1), "cooled down, but beta's vote is open");
    }

    @Test
    void anAdminCancelsAndCantBeKicked() throws Exception{
        TestClient alpha = join("alpha", "127.0.0.1"), beta = join("beta", "127.0.0.2"), boss = join("boss", "127.0.0.4");
        join("gamma", "127.0.0.3");
        Received.admin(Received.session(server, "boss"));
        alpha.chat("/votekick boss afk");
        assertEquals(List.of("[scarlet]Did you really expect to be able to kick an admin?"), Received.messages(alpha, 1));
        alpha.chat("/votekick");
        assertFalse(Received.message(alpha).contains("boss"), "admins aren't listed");

        alpha.chat("/votekick gamma afk");
        heard(beta, "[lightgray]Reason:");
        beta.chat("/vote c");
        assertEquals(List.of("[scarlet]Vote either 'y' (yes) or 'n' (no)."), Received.messages(beta, 1), "only an admin cancels");
        boss.chat("/vote c");
        assertEquals("[lightgray]Vote canceled by admin[orange] boss[lightgray].", heard(alpha, "[lightgray]Vote canceled"));
        heard(beta, "[lightgray]Vote canceled");
        beta.chat("/vote y");
        assertEquals(List.of("[scarlet]Nobody is being voted on."), Received.messages(beta, 1));
    }

    @Test
    void aVoteGoesWithItsWorldWhenTheWorldRetires() throws Exception{
        TestClient alpha = join("alpha", "127.0.0.1"), beta = join("beta", "127.0.0.2"), gamma = join("gamma", "127.0.0.3");
        List<TestClient> all = List.of(alpha, beta, gamma);
        HostedWorld maze = server.host().world("maze").orElseThrow();
        alpha.chat("/votekick gamma griefing");
        for(TestClient client : all) assertEquals(voted("alpha", "gamma", 1), heard(client, "[lightgray]alpha"));
        assertTrue(server.chat().votekick().voting(maze));

        // two rotations before the vote's deadline: maze is reloaded onto fortress, then fortress onto maze
        var retired = new WeakReference<>(maze);
        HostedWorld fortress = rotate(maze, "fortress", all, 3);
        assertFalse(server.chat().votekick().voting(maze), "the vote went with its world");
        maze = null;
        HostedWorld again = rotate(fortress, "maze", all, 5);
        clock.advance(Votekick.DURATION.toNanos() + 1);

        // a vote in the new world starts as any other
        beta.chat("/votekick gamma griefing");
        for(TestClient client : all) assertEquals(voted("beta", "gamma", 1), heard(client, "[lightgray]beta"));
        assertTrue(server.chat().votekick().voting(again));
        await("the retired world to be collected", () -> {
            System.gc();
            return retired.get() == null;
        });
    }

    /** Reloads {@code world} onto {@code map}; every client loads it as its {@code seen}th entry, confirms and plays. */
    HostedWorld rotate(HostedWorld world, String map, List<TestClient> clients, int seen) throws Exception{
        HostedWorld next = server.host().replace(world, server.host().maps().find(map).orElseThrow(), world.mode()).get(Hang.SECONDS, TimeUnit.SECONDS);
        for(TestClient client : clients){
            List<String> loads = client.loads(seen);
            assertEquals("begin", loads.get(seen - 2), loads.toString());
            client.confirm();
        }
        for(String name : List.of("alpha", "beta", "gamma")){
            await(name + " to play in " + map, () -> {
                Session session = Received.session(server, name);
                return session.world() == next && session.state() == SessionState.IN_WORLD && Received.added(session);
            });
        }
        return next;
    }
}
