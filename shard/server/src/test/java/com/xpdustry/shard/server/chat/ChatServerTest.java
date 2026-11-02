package com.xpdustry.shard.server.chat;

import com.xpdustry.shard.server.*;
import com.xpdustry.shard.server.config.*;
import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.server.session.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static com.xpdustry.shard.server.ServerTest.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Chat end to end with byte-level clients: who hears a plain line, a team line ({@code /t}) and an admin line
 * ({@code /a}), each in vanilla's format; vanilla's anti-spam filter at admission; the command handler's replies.
 * Two worlds, so the audiences are checked across them too.
 */
class ChatServerTest{
    @TempDir
    static Path temp;
    static final Map<String, String> ENV = new ConcurrentHashMap<>();
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
            """), ENV, true));
    }

    @AfterAll
    static void stop(){
        if(server != null) server.close();
    }

    @AfterEach
    void settle() throws Exception{
        for(TestClient client : clients) client.close();
        await("the sessions to close", () -> server.sessions().size() == 0);
        if(!ENV.isEmpty()){
            ENV.clear();
            server.config().reload();
        }
        server.admin().pardonAll();
    }

    TestClient join(String name) throws Exception{
        return Received.join(server, clients, name, null);
    }

    static Session session(String name){
        return Received.session(server, name);
    }

    static boolean added(Session session){
        return Received.added(session);
    }

    static int player(Session session){
        return Received.player(session);
    }

    /** Moves {@code session}'s player to {@code team}, as a logged admin command. */
    static void team(Session session, int team) throws Exception{
        HostedWorld world = (HostedWorld)session.world();
        assertTrue(world.managed().offer(new Submission(new SetTeam(session.id(), team), new Cause.Admin("test"))).get(Hang.SECONDS, TimeUnit.SECONDS));
        await(session + " on team " + team, () -> world.inspect(w -> Roster.of(w).player(session.id()).team).orTimeout(Hang.SECONDS, TimeUnit.SECONDS).join() == team);
    }

    record SetTeam(long session, int team) implements Command.Admin{
        @Override
        public Verdict apply(World world, Cause cause){
            Roster.of(world).player(session).team(team);
            return Verdict.ADMITTED;
        }
    }

    static void set(ConfigKey<?> key, String value) throws ConfigException{
        ENV.put(key.env(), value);
        server.config().reload();
    }

    @Test
    void aLineReachesItsWorldInVanillasFormat() throws Exception{
        TestClient alpha = join("alpha"), beta = join("beta");
        // fortress: join is first-fit, so move gamma there
        TestClient gamma = join("gamma");
        gamma.chat("/world fortress");
        await("gamma in fortress", () -> session("gamma").world() == server.host().world("fortress").orElseThrow() && session("gamma").state() == SessionState.LOADING);
        gamma.loads(3);
        gamma.confirm();
        await("gamma to play in fortress", () -> session("gamma").state() == SessionState.IN_WORLD && added(session("gamma")));
        Received.forget(gamma);

        alpha.chat("hello\nthere");
        int sender = player(session("alpha"));
        String formatted = "[coral][[[#D37FFFFF]alpha[coral]]:[white] hellothere";
        assertEquals(new Received.Line(formatted, "hellothere", sender), Received.line(alpha), "the speaker hears itself");
        assertEquals(new Received.Line(formatted, "hellothere", sender), Received.line(beta));
        assertFalse(Received.anyLine(gamma, 500), "another world doesn't");

        // over maxTextLength: vanilla's handler throws, nothing is said
        alpha.chat("x".repeat(Chat.MAX_TEXT_LENGTH + 1));
        assertFalse(Received.anyLine(beta, 500));
    }

    @Test
    void teamChatReachesTheTeamOnly() throws Exception{
        TestClient alpha = join("alpha"), beta = join("beta"), delta = join("delta");
        team(session("delta"), 2);
        alpha.chat("/t push left");
        String raw = "[#ffd37fff]<T> [coral][[[#D37FFFFF]alpha[coral]]:[white] push left";
        assertEquals(new Received.Line(raw, "push left", player(session("alpha"))), Received.line(alpha));
        assertEquals(new Received.Line(raw, "push left", player(session("alpha"))), Received.line(beta));
        assertFalse(Received.anyLine(delta, 500), "crux doesn't hear sharded's team chat");

        delta.chat("/t");
        assertEquals(List.of("[scarlet]Too few arguments. Usage:[lightgray] t[gray] <message...>"), Received.messages(delta, 1));
    }

    @Test
    void adminChatReachesTheAdminsOfEveryWorld() throws Exception{
        TestClient boss = join("boss"), alpha = join("alpha"), chief = join("chief");
        Received.admin(session("boss"));
        // a second admin, in the other world: its next world's join makes its player one
        session("chief").admin(true);
        chief.chat("/world fortress");
        await("chief in fortress", () -> session("chief").state() == SessionState.LOADING);
        chief.loads(3);
        chief.confirm();
        await("chief to play in fortress", () -> session("chief").state() == SessionState.IN_WORLD && added(session("chief")));
        Received.forget(chief);

        alpha.chat("/a let me in");
        assertEquals(List.of("[scarlet]You must be an admin to use this command."), Received.messages(alpha, 1));

        boss.chat("/a heads up");
        String raw = "[#ff4000ff]<A> [coral][[[#D37FFFFF]boss[coral]]:[white] heads up";
        assertEquals(new Received.Line(raw, "heads up", player(session("boss"))), Received.line(boss));
        assertEquals(new Received.Line(raw, "heads up", -1), Received.line(chief), "elsewhere, without a sender");
        assertFalse(Received.anyLine(alpha, 500), "not an admin");
    }

    @Test
    void theAntiSpamFilterRunsAtAdmission() throws Exception{
        set(ConfigKeys.CHAT_MESSAGE_RATE_LIMIT, "2s");
        TestClient alpha = join("alpha"), beta = join("beta");
        alpha.chat("one");
        assertEquals("one", Received.line(beta).unformatted());
        alpha.chat("two");
        assertEquals(List.of("[scarlet]You may only send messages every 2 seconds."), Received.messages(alpha, 1));
        assertFalse(Received.anyLine(beta, 200));

        set(ConfigKeys.CHAT_MESSAGE_RATE_LIMIT, "0s");
        alpha.chat("three");
        assertEquals("three", Received.line(beta).unformatted());
        alpha.chat("three");
        assertEquals(List.of(AntiSpam.REPEATED), Received.messages(alpha, 1));
        alpha.chat("/t three");
        assertEquals(List.of(AntiSpam.REPEATED), Received.messages(alpha, 1), "/t's message is filtered too");
    }

    @Test
    void spammingPastTheLimitKicks() throws Exception{
        set(ConfigKeys.CHAT_MESSAGE_RATE_LIMIT, "60s");
        TestClient alpha = join("alpha");
        alpha.chat("first");
        for(int i = 0; i < 3; i++) alpha.chat("again " + i);
        assertEquals(AntiSpam.KICK_REASON, Received.kick(alpha));
        assertTrue(alpha.closedWithin(Hang.MILLIS));
        // the cooldown is written as the kick goes out
        await("the kick's cooldown", () -> server.admin().kickedUntil("", "127.0.0.1") > System.currentTimeMillis());
        long cooldown = server.admin().kickedUntil("", "127.0.0.1") - System.currentTimeMillis();
        // two minutes, not the 30 s of a plain kick; half of them is slack for a test the machine delays, a hang's worth
        assertTrue(cooldown > AntiSpam.KICK_MILLIS / 2 && cooldown <= AntiSpam.KICK_MILLIS, "two minutes: " + cooldown);
    }

    @Test
    void commandsAnswerAsVanillasHandler() throws Exception{
        TestClient alpha = join("alpha");
        alpha.chat("/hepl");
        assertEquals(List.of("[scarlet]Unknown command. Did you mean \"[lightgray]help[]\"?"), Received.messages(alpha, 1));
        alpha.chat("/xyzzy");
        assertEquals(List.of("[scarlet]Unknown command. Check [lightgray]/help[scarlet]."), Received.messages(alpha, 1));
        alpha.chat("/sync now");
        assertEquals(List.of("[scarlet]Too many arguments. Usage:[lightgray] sync[gray] "), Received.messages(alpha, 1));
        alpha.chat("/help");
        String page = Received.message(alpha);
        assertTrue(page.startsWith("[orange]-- Commands Page[lightgray] 1[gray]/[lightgray]2[orange] --\n\n[orange] /help[white] [page][lightgray] - Lists all commands.\n"), page);
        assertTrue(page.contains("[orange] /t[white] <message...>[lightgray] - Send a message only to your teammates.\n"), page);
        alpha.chat("/help 2");
        assertEquals("[orange]-- Commands Page[lightgray] 2[gray]/[lightgray]2[orange] --\n\n[orange] /world[white] [name][lightgray] - Lists the worlds, or moves you to one.\n",
            Received.message(alpha));
        alpha.chat("/help x");
        assertEquals(List.of("[scarlet]'page' must be a number."), Received.messages(alpha, 1));

        // a plugin's command
        server.chat().commands().register("ping", "<what...>", "Pongs.", (args, c) -> c.reply("pong " + args[0]));
        try{
            alpha.chat("/ping a b");
            assertEquals(List.of("pong a b"), Received.messages(alpha, 1));
        }finally{
            server.chat().commands().remove("ping");
        }
    }
}
