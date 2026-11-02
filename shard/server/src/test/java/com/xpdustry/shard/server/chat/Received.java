package com.xpdustry.shard.server.chat;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.net.Frame;
import com.xpdustry.shard.server.*;
import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.server.session.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.Codecs;
import com.xpdustry.shard.wire.v160_7.Packets;

import java.net.*;
import java.util.*;
import java.util.concurrent.*;

import static com.xpdustry.shard.server.ServerTest.await;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Byte-level players for the chat tests: joining one from a given loopback address, finding its session and player,
 * and what it hears in chat (server lines, {@code sendMessage(text)}, and player lines), decoded.
 */
final class Received{
    /** Connects {@code name} from {@code local} (null for any), loads its world, confirms and waits until it plays. */
    static TestClient join(Server server, List<TestClient> clients, String name, String local) throws Exception{
        var client = new TestClient(server.transport().address(), local == null ? null : InetAddress.getByName(local));
        clients.add(client);
        client.connect(name);
        client.world();
        client.confirm();
        await(name + " to play", () -> session(server, name) != null && session(server, name).state() == SessionState.IN_WORLD && added(session(server, name)));
        return client;
    }

    static Session session(Server server, String name){
        return server.sessions().all().stream().filter(s -> s.identity().plainName().equals(name)).findFirst().orElse(null);
    }

    /** Whether the session's world has added its player: a tick after it goes {@code IN_WORLD}. */
    static boolean added(Session session){
        if(!(session.world() instanceof HostedWorld world)) return false;
        var player = world.inspect(w -> Roster.of(w).player(session.id())).orTimeout(Hang.SECONDS, TimeUnit.SECONDS).join();
        return player != null && player.isAdded();
    }

    /** The session's player id in its world. */
    static int player(Session session){
        HostedWorld world = (HostedWorld)session.world();
        return world.inspect(w -> Roster.of(w).player(session.id()).id()).orTimeout(Hang.SECONDS, TimeUnit.SECONDS).join();
    }

    /** Makes {@code session} an admin: the session's flag, and its player's through a logged admin command. */
    static void admin(Session session) throws Exception{
        session.admin(true);
        HostedWorld world = (HostedWorld)session.world();
        assertTrue(world.managed().offer(new Submission(new SetAdmin(session.id()), new Cause.Admin("test"))).get(Hang.SECONDS, TimeUnit.SECONDS));
        await(session + " to be an admin", () -> world.inspect(w -> Roster.of(w).player(session.id()).admin).orTimeout(Hang.SECONDS, TimeUnit.SECONDS).join());
    }

    record SetAdmin(long session) implements Command.Admin{
        @Override
        public Verdict apply(World world, Cause cause){
            Roster.of(world).player(session).admin = true;
            return Verdict.ADMITTED;
        }
    }

    /** {@code sendMessage(message, unformatted, sender)}. */
    record Line(String message, String unformatted, int sender){
    }

    /** The next player line, however long it takes (a hang is the only timeout). */
    static Line line(TestClient client) throws InterruptedException{
        byte[] body = client.queue(Packets.SendMessage2.ID).poll(Hang.SECONDS, TimeUnit.SECONDS);
        if(body == null) fail("no player line within " + Hang.SECONDS + " s");
        var read = new Reads(Frame.decodePayload(body, 1, body.length - 1));
        return new Line(Codecs.readString(read), Codecs.readString(read), read.i());
    }

    /**
     * How long {@link #messages} and {@link #quiet} look for a line that shouldn't be there. This is an absence window:
     * a loaded machine can only make it look less far, never fail a test, and what must arrive is waited for by count.
     */
    static final long GRACE_MILLIS = 200;

    /** Whether a player line arrives within {@code millis}: an absence window for a line that shouldn't come. */
    static boolean anyLine(TestClient client, long millis) throws InterruptedException{
        return client.queue(Packets.SendMessage2.ID).poll(millis, TimeUnit.MILLISECONDS) != null;
    }

    /**
     * The next {@code count} server lines, in order, however long they take (a hang is the only timeout), and then any
     * that arrive right behind them within {@link #GRACE_MILLIS}: a test that expects exactly {@code count} lines sees
     * the extra ones in the list.
     */
    static List<String> messages(TestClient client, int count) throws InterruptedException{
        var out = new ArrayList<String>();
        while(out.size() < count) out.add(message(client));
        out.addAll(drain(client, GRACE_MILLIS));
        return out;
    }

    /** The next server line, however long it takes (a hang is the only timeout). */
    static String message(TestClient client) throws InterruptedException{
        byte[] body = client.queue(Packets.SendMessage.ID).poll(Hang.SECONDS, TimeUnit.SECONDS);
        if(body == null) fail("no server line within " + Hang.SECONDS + " s");
        return Codecs.readString(new Reads(Frame.decodePayload(body, 1, body.length - 1)));
    }

    /** Whether no server line arrives within {@link #GRACE_MILLIS}: an absence window. */
    static boolean quiet(TestClient client) throws InterruptedException{
        return drain(client, GRACE_MILLIS).isEmpty();
    }

    /** The server lines that arrive within {@code millis}, in order. A poll slice for a loop with its own deadline. */
    static List<String> drain(TestClient client, long millis) throws InterruptedException{
        var out = new ArrayList<String>();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while(System.nanoTime() < deadline){
            byte[] body = client.queue(Packets.SendMessage.ID).poll(20, TimeUnit.MILLISECONDS);
            if(body != null) out.add(Codecs.readString(new Reads(Frame.decodePayload(body, 1, body.length - 1))));
        }
        return out;
    }

    /** The text of the next {@code kick(reason)}. */
    static String kick(TestClient client) throws InterruptedException{
        return Codecs.readString(new Reads(client.payload(Packets.Kick.ID)));
    }

    /** Drops what the client heard so far. */
    static void forget(TestClient client){
        client.queue(Packets.SendMessage.ID).clear();
        client.queue(Packets.SendMessage2.ID).clear();
    }

    private Received(){}
}
