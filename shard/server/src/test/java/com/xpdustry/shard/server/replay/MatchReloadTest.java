package com.xpdustry.shard.server.replay;

import com.xpdustry.shard.server.*;
import com.xpdustry.shard.server.config.*;
import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.server.world.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static com.xpdustry.shard.server.ServerTest.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A game over's reload ends one match and starts another. {@link Host#replace} puts the world on the rotation's next
 * map under the same id, and the recorder closes the old match's replay at the reload and records the new match under
 * an id of its own, though the reload waits no time and both start in the same second. With {@code persistence.resume}
 * on, a reload onto the same map and mode starts over too: an ended match's checkpoint says so, and nothing resumes it.
 */
class MatchReloadTest{
    @TempDir
    static Path temp;
    static Server server;
    static ReplayStore replays;

    @BeforeAll
    static void start() throws Exception{
        Path root = ServerTest.configure(temp.resolve("main"), "");
        Files.createDirectories(root.resolve("worlds"));
        Files.writeString(root.resolve("worlds").resolve("loop.yml"), """
            game:
              maps: [maze, fortress]
              shuffle: all
              round-extra-time: 0s
            """);
        Path dir = temp.resolve("replays");
        replays = new ReplayStore(dir);
        server = Server.start(new Server.Options(root, Map.of(ConfigKeys.REPLAY_RECORD.env(), "true", ConfigKeys.REPLAY_DIRECTORY.env(), dir.toString(),
            ConfigKeys.PERSISTENCE_RESUME.env(), "true", ConfigKeys.GAME_SIMULATE_WHEN_EMPTY.env(), "true"), true));
    }

    @AfterAll
    static void stop(){
        if(server != null) server.close();
    }

    @Test
    void aGameOversReloadEndsTheReplayAndRecordsTheNextMatch() throws Exception{
        HostedWorld maze = server.host().host(server.host().spec("loop=maze survival")).get(Hang.SECONDS, TimeUnit.SECONDS);
        String first = match(maze);
        HostedWorld fortress = gameOver(maze, "Fortress");
        String second = match(fortress);
        assertNotEquals(first, second, "the next map is a match of its own");

        // the console's nextmap brings the same map and mode back: resume is on, but an ended match never resumes
        server.game().rotation().next(fortress.id(), server.host().maps().find("fortress").orElseThrow());
        HostedWorld again = gameOver(fortress, "Fortress");
        String third = match(again);
        assertNotEquals(second, third, "a resumed world would have gone on with the ended match");
        assertTrue(third.startsWith("loop-"), third);

        // the two ended matches are over and remade byte for byte from their bundles; the third records
        for(String ended : List.of(first, second)){
            Bundle bundle = replays.bundle(ended);
            await(ended + " to end", () -> over(bundle));
            Regenerator.Report report = new Regenerator(bundle).verify(line -> {});
            assertTrue(report.ok(), ended + ": " + report.problems());
        }
        assertEquals("Maze", replays.bundle(first).manifest().mapName());
        assertEquals("Fortress", replays.bundle(second).manifest().mapName());
        Bundle current = replays.bundle(third);
        await(third + " to record", current::exists);
        assertFalse(current.manifest().over());
        assertEquals("Fortress", current.manifest().mapName());
    }

    /** Ends {@code world}'s game from the console and returns the world reloaded onto {@code next}, running. */
    static HostedWorld gameOver(HostedWorld world, String next) throws Exception{
        // a match that ran a while: its replay has frames past its first keyframe
        await(world.id() + " to tick", () -> ticks(world) > 120);
        assertTrue(server.game().force(world).get(Hang.SECONDS, TimeUnit.SECONDS));
        await(world.id() + " to reload onto " + next, () -> {
            HostedWorld now = server.host().world(world.id().name()).orElse(null);
            return now != null && now != world && now.map().name().equals(next) && now.managed() != null && now.managed().match() != null
                && !server.game().waiting(world.id());
        });
        return server.host().world(world.id().name()).orElseThrow();
    }

    static String match(HostedWorld world){
        return world.managed().match();
    }

    static long ticks(HostedWorld world){
        return world.inspect(World::ticks).exceptionally(failure -> -1L).join();
    }

    static boolean over(Bundle bundle){
        try{
            return bundle.exists() && bundle.manifest().over();
        }catch(java.io.IOException e){
            return false;
        }
    }
}
