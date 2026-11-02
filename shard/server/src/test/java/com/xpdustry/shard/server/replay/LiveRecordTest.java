package com.xpdustry.shard.server.replay;

import com.xpdustry.shard.server.*;
import com.xpdustry.shard.server.config.*;
import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static com.xpdustry.shard.server.ServerTest.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code replay.record} and {@code persistence.match-checkpoints} are per-world keys that reload live, so a server
 * started with nothing recording starts recording a world at its next keyframe once that world's config says so, in the
 * match it is playing, and stops when it says no. A world's own {@code worlds/<id>.yml} decides for that world only.
 */
class LiveRecordTest{
    @TempDir
    static Path temp;
    static Path worlds;
    static Server server;
    static ReplayStore replays;

    @BeforeAll
    static void start() throws Exception{
        Path root = ServerTest.configure(temp.resolve("main"), "");
        worlds = Files.createDirectories(root.resolve("worlds"));
        Files.writeString(worlds.resolve("on.yml"), "game:\n  round-extra-time: 0s\n");
        Files.writeString(worlds.resolve("off.yml"), "game:\n  round-extra-time: 0s\n");
        // both record from their match's start; only one sets its own checkpoint interval
        Files.writeString(worlds.resolve("slow.yml"), "game:\n  round-extra-time: 0s\nreplay:\n  record: true\npersistence:\n  match-checkpoints: 20s\n");
        Files.writeString(worlds.resolve("fast.yml"), "game:\n  round-extra-time: 0s\nreplay:\n  record: true\n");
        Path dir = temp.resolve("replays");
        replays = new ReplayStore(dir);
        // nothing records: the host-level config has replay.record and persistence.match-checkpoints at their defaults
        server = Server.start(new Server.Options(root, Map.of(ConfigKeys.REPLAY_DIRECTORY.env(), dir.toString(), ConfigKeys.GAME_SIMULATE_WHEN_EMPTY.env(), "true"), true));
    }

    @AfterAll
    static void stop(){
        if(server != null) server.close();
    }

    @Test
    void aReloadStartsRecordingAWorldInItsCurrentMatchAndAnotherReloadStopsIt() throws Exception{
        HostedWorld on = server.host().host(server.host().spec("on=maze survival")).get(Hang.SECONDS, TimeUnit.SECONDS);
        HostedWorld off = server.host().host(server.host().spec("off=maze survival")).get(Hang.SECONDS, TimeUnit.SECONDS);
        String onMatch = on.managed().match(), offMatch = off.managed().match();
        await("both worlds to play", () -> MatchReloadTest.ticks(on) > 120 && MatchReloadTest.ticks(off) > 120);
        assertFalse(replays.bundle(onMatch).exists(), "nothing records yet");

        // only this world's file turns it on; the next keyframe is where its recording starts
        Files.writeString(worlds.resolve("on.yml"), "game:\n  round-extra-time: 0s\nreplay:\n  record: true\n");
        server.config().reload();
        long reloaded = MatchReloadTest.ticks(on);
        long start = Math.ceilDiv(reloaded, 600L) * 600L;
        Bundle bundle = replays.bundle(onMatch);
        // a whole segment is past its keyframe once the world has played a keyframe beyond it
        await("the recording to close a segment", () -> MatchReloadTest.ticks(on) > start + 600 + 60 && bundle.exists() && segments(bundle) > 0);
        Bundle.Manifest recording = bundle.manifest();
        assertFalse(recording.over());
        assertEquals(onMatch, recording.match(), "the match under way, not a new one");
        assertEquals(start, recording.start(), "from the next keyframe");
        assertEquals(600, recording.checkpointEvery(), "recording turned match checkpoints on at 10 s");
        assertTrue(bundle.checkpoints().contains(start), "the checkpoint it starts from: " + bundle.checkpoints());
        assertFalse(replays.bundle(offMatch).exists(), "a world whose file doesn't say so records nothing: " + offMatch);

        // off again: the bundle is closed at the tick it stopped, readable, and nothing is added to it
        Files.writeString(worlds.resolve("on.yml"), "game:\n  round-extra-time: 0s\nreplay:\n  record: false\n");
        server.config().reload();
        await("the bundle to end and bake", () -> baked(bundle));
        Bundle.Manifest ended = bundle.manifest();
        assertEquals(onMatch, ended.match());
        assertEquals(-1, ended.winner(), "no game over: it was stopped");
        assertTrue(ended.end() > start + 600, "it ran a full segment: " + ended);
        assertEquals(600, ended.checkpointEvery(), "the bundle keeps the interval it started with");
        var segments = bundle.segmentFiles();
        var checkpoints = bundle.checkpoints();
        assertFalse(segments.isEmpty());
        assertFalse(bundle.segments().values().stream().anyMatch(Bundle.SegmentEntry::partial), "the last segment was closed whole: " + bundle.segments());
        Regenerator.Report report = new Regenerator(bundle).verify(line -> {});
        assertTrue(report.ok(), "the bundle replays: " + report.problems());

        long stopped = MatchReloadTest.ticks(on);
        await("the world to play past another keyframe", () -> MatchReloadTest.ticks(on) > Math.ceilDiv(stopped, 600L) * 600L + 60);
        assertEquals(segments, bundle.segmentFiles(), "no segment after it stopped");
        assertEquals(checkpoints, bundle.checkpoints(), "no checkpoint after it stopped");
        assertEquals(ended.end(), bundle.manifest().end(), "the bundle is as it was closed");
        assertFalse(replays.bundle(offMatch).exists(), "the other world never recorded");
    }

    @Test
    void aWorldsOwnMatchCheckpointsSetItsIntervalAndNotAnothersWorld() throws Exception{
        HostedWorld slow = server.host().host(server.host().spec("slow=maze survival")).get(Hang.SECONDS, TimeUnit.SECONDS);
        HostedWorld fast = server.host().host(server.host().spec("fast=maze survival")).get(Hang.SECONDS, TimeUnit.SECONDS);
        Bundle slowBundle = replays.bundle(slow.managed().match()), fastBundle = replays.bundle(fast.managed().match());
        // a count of ticks, not a deadline: the slow world has played past a second 20 s checkpoint, the fast one past two 10 s ones
        await("both worlds to checkpoint", () -> MatchReloadTest.ticks(slow) > 2400 + 60 && slowBundle.exists() && fastBundle.exists()
            && checkpointCount(slowBundle) >= 2 && checkpointCount(fastBundle) >= 2);
        Bundle.Manifest slowManifest = slowBundle.manifest(), fastManifest = fastBundle.manifest();
        assertEquals(1200, slowManifest.checkpointEvery(), "persistence.match-checkpoints: 20s in its own file");
        assertEquals(600, fastManifest.checkpointEvery(), "the other world keeps the 10 s a recording turns on");
        var slowCheckpoints = slowBundle.checkpoints().stream().toList();
        for(int i = 1; i < slowCheckpoints.size(); i++){
            assertEquals(1200L, slowCheckpoints.get(i) - slowCheckpoints.get(i - 1), "the slow world's checkpoints are 1200 ticks apart: " + slowCheckpoints);
        }
        var fastCheckpoints = fastBundle.checkpoints().stream().toList();
        assertEquals(600L, fastCheckpoints.get(1) - fastCheckpoints.get(0), "the fast world's are 600 apart: " + fastCheckpoints);
    }

    static int checkpointCount(Bundle bundle){
        try{
            return bundle.checkpoints().size();
        }catch(java.io.IOException e){
            return 0;
        }
    }

    static boolean baked(Bundle bundle){
        try{
            return bundle.exists() && bundle.manifest().over() && bundle.manifest().baked();
        }catch(java.io.IOException e){
            return false;
        }
    }

    static int segments(Bundle bundle){
        try{
            return bundle.segmentFiles().size();
        }catch(java.io.IOException e){
            return 0;
        }
    }
}
