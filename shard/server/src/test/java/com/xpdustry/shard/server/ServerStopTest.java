package com.xpdustry.shard.server;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.server.console.Console;
import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.server.session.*;
import com.xpdustry.shard.server.world.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.Codecs;
import com.xpdustry.shard.wire.v160_7.KickReason;
import com.xpdustry.shard.wire.v160_7.Packets;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Worlds that fill, stop, fail and come back: the console's {@code stop}, {@code host} and {@code recover}, and the close. */
class ServerStopTest{
    @TempDir
    Path temp;

    @Test
    void fullStoppedRehostedClosed() throws Exception{
        Server server = Server.start(new Server.Options(ServerTest.configure(temp, """
            host:
              worlds: [maze]
              world-player-limit: 1
            """), Map.of(), false));
        var out = new ByteArrayOutputStream();
        var console = new Console(server, new PrintStream(out, true, StandardCharsets.UTF_8), () -> {});
        try{
            try(var alpha = new TestClient(server.transport().address())){
                alpha.connect("alpha");
                alpha.world();
                alpha.confirm();
                // the only world is full: vanilla's playerLimit, though the host has room
                try(var beta = new TestClient(server.transport().address())){
                    beta.connect("beta");
                    assertEquals(KickReason.playerLimit, KickReason.of(beta.kickReason()));
                    assertTrue(beta.closedWithin(Hang.MILLIS));
                }

                console.run("stop maze");
                assertEquals(KickReason.serverClose, KickReason.of(alpha.kickReason()));
                assertTrue(alpha.closedWithin(Hang.MILLIS));
            }
            assertTrue(out.toString(StandardCharsets.UTF_8).contains("Stopped maze."), out.toString(StandardCharsets.UTF_8));
            assertTrue(server.host().worlds().isEmpty());

            try(var client = new TestClient(server.transport().address())){
                client.connect("alpha");
                assertEquals("[scarlet]No world is running here right now.", Codecs.readString(new Reads(client.payload(Packets.KICK))));
            }

            console.run("host maze");
            assertTrue(out.toString(StandardCharsets.UTF_8).contains("Map loaded: maze: Maze (survival)"), out.toString(StandardCharsets.UTF_8));
            try(var client = new TestClient(server.transport().address())){
                client.connect("alpha");
                assertEquals("Maze", client.world().tags().get("name"));
                client.confirm();
                ServerTest.await("alpha to play", () -> server.sessions().all().stream().anyMatch(s -> s.state() == SessionState.IN_WORLD));

                server.close();
                assertEquals(KickReason.serverClose, KickReason.of(client.kickReason()));
                assertTrue(client.closedWithin(Hang.MILLIS));
            }
        }finally{
            server.close();
        }
    }

    /** A bug in the simulation: the tick that admits it throws. */
    record Boom() implements Command.Internal{
        @Override
        public Verdict apply(World world, Cause cause){
            throw new IllegalStateException("boom");
        }
    }

    @Test
    void aFailedWorldKicksItsPlayersAndComesBackOnRecover() throws Exception{
        Server server = Server.start(new Server.Options(ServerTest.configure(temp, """
            host:
              worlds: [maze, fortress]
              join: first
            """), Map.of(), false));
        var out = new ByteArrayOutputStream();
        var console = new Console(server, new PrintStream(out, true, StandardCharsets.UTF_8), () -> {});
        try{
            HostedWorld maze = server.host().world("maze").orElseThrow();
            try(var alpha = new TestClient(server.transport().address())){
                alpha.connect("alpha");
                alpha.world();
                alpha.confirm();
                ServerTest.await("alpha to play", () -> server.sessions().all().stream().anyMatch(s -> s.state() == SessionState.IN_WORLD));
                assertTrue(maze.managed().offer(new Submission(new Boom(), Cause.SERVER)).get());
                assertEquals("[scarlet]This world stopped after an error.[]\nReconnect to join another.",
                    Codecs.readString(new Reads(alpha.payload(Packets.KICK))));
                assertTrue(alpha.closedWithin(Hang.MILLIS));
            }
            assertEquals(Lifecycle.FAILED, maze.managed().lifecycle());
            assertFalse(maze.available());
            try(var beta = new TestClient(server.transport().address())){
                beta.connect("beta");
                assertEquals("Fortress", beta.world().tags().get("name"), "the others play on");
            }
            ServerTest.await("beta to leave", () -> server.sessions().size() == 0);

            console.run("recover maze");
            assertTrue(out.toString(StandardCharsets.UTF_8).contains("Recovered maze: Maze"), out.toString(StandardCharsets.UTF_8));
            assertTrue(maze.available());
            try(var gamma = new TestClient(server.transport().address())){
                gamma.connect("gamma");
                assertEquals("Maze", gamma.world().tags().get("name"));
                gamma.confirm();
                // the checkpoint still listed alpha: the recovered world let it go
                ServerTest.await("only gamma in maze", () -> {
                    try{
                        return maze.inspect(w -> Roster.of(w).size() == 1 && w.members().size() == 1).get();
                    }catch(Exception e){
                        throw new AssertionError(e);
                    }
                });
            }
        }finally{
            server.close();
        }
    }
}
