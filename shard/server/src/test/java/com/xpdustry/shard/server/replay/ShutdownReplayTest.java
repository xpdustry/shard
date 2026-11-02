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

import static org.junit.jupiter.api.Assertions.*;

/**
 * A server stopping without {@code persistence.resume} ends and bakes every recorded match, including one whose world
 * hibernated while empty before the stop: that world doesn't hibernate again at the stop, so only {@link Replays#closing}
 * can end it. Found on the live test server, where four such bundles were left unbaked by a redeploy.
 */
class ShutdownReplayTest{
    @Test
    void aStopWithoutResumeBakesTheMatchOfAWorldThatHibernatedBefore(@TempDir Path temp) throws Exception{
        Path dir = temp.resolve("replays");
        var replays = new ReplayStore(dir);
        Server server = Server.start(new Server.Options(ServerTest.configure(temp.resolve("main"), """
            host:
              worlds: [quiet=maze survival]
            replay:
              record: true
            """), Map.of(ConfigKeys.REPLAY_DIRECTORY.env(), dir.toString(), ConfigKeys.PERSISTENCE_HIBERNATE_AFTER.env(), "1s"), false));
        Bundle bundle;
        try{
            HostedWorld quiet = server.host().world("quiet").orElseThrow();
            bundle = replays.bundle(quiet.managed().match());
            // empty, it idles, then hibernates (at the policy's floor), which suspends its recording
            long deadline = System.nanoTime() + Hang.TIMEOUT.toNanos();
            while(quiet.managed().lifecycle() != Lifecycle.HIBERNATED){
                if(System.nanoTime() > deadline) fail("never hibernated: " + quiet.managed().lifecycle());
                Thread.sleep(20);
            }
            assertTrue(bundle.exists(), "it recorded");
            assertEquals("suspended", bundle.manifest().state(), "hibernating suspends the recording");
        }finally{
            server.close();
        }
        Bundle.Manifest ended = bundle.manifest();
        assertTrue(ended.over(), "the stop ended it: " + ended);
        assertEquals(-1, ended.winner(), "no game over");
        assertTrue(ended.baked(), "its view stream is baked: " + ended);
        assertTrue(Files.isRegularFile(dir.resolve("views").resolve(ended.match() + ".sview")), "the baked view is published");
    }
}
