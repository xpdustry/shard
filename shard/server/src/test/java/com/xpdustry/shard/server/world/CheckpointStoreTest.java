package com.xpdustry.shard.server.world;

import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** {@link CheckpointStore}: publication is all or nothing, and loading survives damaged generations. */
class CheckpointStoreTest{
    static final WorldId ID = WorldId.of("store-test");

    @TempDir
    Path root;

    /** A checkpoint file of a test world after {@code ticks} ticks. */
    static byte[] checkpoint(int ticks){
        var world = new World(TestWorlds.config(3));
        for(Submission build : TestWorlds.crates(5)) world.inbox().offer(build);
        for(int i = 0; i < ticks; i++) world.tick();
        return Checkpoint.capture(world).encode(Compression.FAST);
    }

    /** The real file system, with one step that can be made to fail. */
    static final class Faulty implements FileOps{
        String failOn;
        boolean shortWrites;

        void check(String step, Path file) throws IOException{
            if(step.equals(failOn) || (step + ":" + file.getFileName()).equals(failOn)) throw new IOException("injected " + step + " failure on " + file.getFileName());
        }

        @Override
        public void forceDirectory(Path dir) throws IOException{
            check("force", dir);
        }

        @Override
        public void write(Path file, byte[] bytes) throws IOException{
            check("write", file);
            NoSync.INSTANCE.write(file, shortWrites ? Arrays.copyOf(bytes, bytes.length / 2) : bytes);
        }

        @Override
        public byte[] read(Path file) throws IOException{
            check("read", file);
            return SYSTEM.read(file);
        }

        @Override
        public void move(Path from, Path to) throws IOException{
            check("move", to);
            SYSTEM.move(from, to);
        }


        @Override
        public void delete(Path file) throws IOException{
            SYSTEM.delete(file);
        }

        @Override
        public List<String> list(Path dir) throws IOException{
            return SYSTEM.list(dir);
        }

        @Override
        public void createDirectories(Path dir) throws IOException{
            SYSTEM.createDirectories(dir);
        }
    }

    @Test
    void publishingMakesEachGenerationCurrentAndKeepsTheNewest() throws IOException{
        // The real, syncing file system, once.
        var store = new CheckpointStore(root, FileOps.SYSTEM, 3);
        assertEquals(Optional.empty(), store.load(ID));
        byte[] last = null;
        for(int i = 1; i <= 5; i++){
            last = checkpoint(i * 10);
            CheckpointStore.Generation generation = store.publish(ID, last);
            assertEquals(i, generation.number());
            assertEquals(i * 10, generation.header().tick());
        }
        assertEquals(List.of("gen-000000000003.ssnp", "gen-000000000004.ssnp", "gen-000000000005.ssnp"), store.generations(ID));
        assertEquals("gen-000000000005.ssnp", Files.readString(store.dir(ID).resolve("CURRENT")).strip());
        CheckpointStore.Loaded loaded = store.load(ID).orElseThrow();
        assertEquals(5, loaded.generation().number());
        assertArrayEquals(last, loaded.bytes());
        assertNull(loaded.recovery());
    }

    @Test
    void aFailureAtAnyStepKeepsTheLastGoodGeneration() throws IOException{
        var files = new Faulty();
        var store = new CheckpointStore(root, files, 3);
        byte[] good = checkpoint(10);
        store.publish(ID, good);
        byte[] next = checkpoint(20);
        for(String step : List.of("write:gen-000000000002.ssnp.tmp", "move:gen-000000000002.ssnp", "force", "read:gen-000000000002.ssnp",
            "write:CURRENT.tmp", "move:CURRENT")){
            files.failOn = step;
            IOException failure = assertThrows(IOException.class, () -> store.publish(ID, next), step);
            assertTrue(failure.getMessage().contains("injected"), step + ": " + failure.getMessage());
            files.failOn = null;
            CheckpointStore.Loaded loaded = store.load(ID).orElseThrow();
            assertEquals(1, loaded.generation().number(), step);
            assertArrayEquals(good, loaded.bytes(), step);
            assertNull(loaded.recovery(), step);
            assertEquals(List.of("CURRENT", "gen-000000000001.ssnp"), FileOps.SYSTEM.list(store.dir(ID)), step + " left files behind");
        }
        assertEquals(2, store.publish(ID, next).number(), "it publishes once the fault is gone");
    }

    @Test
    void aShortWriteIsCaughtByTheReadBack() throws IOException{
        var files = new Faulty();
        var store = new CheckpointStore(root, files, 3);
        byte[] good = checkpoint(10);
        store.publish(ID, good);
        files.shortWrites = true;
        IOException failure = assertThrows(IOException.class, () -> store.publish(ID, checkpoint(20)));
        assertTrue(failure.getMessage().contains("reads back different bytes"), failure.getMessage());
        files.shortWrites = false;
        assertArrayEquals(good, store.load(ID).orElseThrow().bytes());
    }

    @Test
    void aCaptureOlderThanTheCurrentGenerationIsRefused() throws IOException{
        var store = new CheckpointStore(root, NoSync.INSTANCE, 3);
        byte[] newer = checkpoint(20);
        store.publish(ID, newer);
        var stale = assertThrows(CheckpointStore.StaleCaptureException.class, () -> store.publish(ID, checkpoint(10)));
        assertEquals(10, stale.tick());
        assertEquals(20, stale.currentTick());
        assertTrue(stale.getMessage().contains("gen-000000000001.ssnp at tick 20"), stale.getMessage());
        assertEquals(List.of("CURRENT", "gen-000000000001.ssnp"), FileOps.SYSTEM.list(store.dir(ID)), "nothing was written");
        assertArrayEquals(newer, store.load(ID).orElseThrow().bytes());

        assertEquals(2, store.publish(ID, checkpoint(20)).number(), "the same tick is not older");
        assertEquals(3, store.publish(ID, checkpoint(30)).number());
        Path current = store.dir(ID).resolve("gen-000000000003.ssnp");
        Files.write(current, new byte[]{1, 2, 3});
        assertEquals(4, store.publish(ID, checkpoint(10)).number(), "an unreadable current generation refuses nothing");
    }

    @Test
    void archivingMovesTheGenerationsAsideForAFreshStart() throws IOException{
        var store = new CheckpointStore(root, NoSync.INSTANCE, 3);
        assertNull(store.archive(ID, 5), "nothing to move");
        byte[] old = checkpoint(20);
        store.publish(ID, old);
        Path archived = store.archive(ID, 1234);
        assertEquals(root.resolve(ID.name() + ".replaced-1234"), archived);
        assertTrue(store.load(ID).isEmpty());
        assertEquals(1, store.publish(ID, checkpoint(10)).number(), "a fresh world starts over");
        assertArrayEquals(old, Files.readAllBytes(archived.resolve("gen-000000000001.ssnp")));
    }

    @Test
    void aTruncatedCurrentGenerationFallsBackToTheNewestReadableOne() throws IOException{
        var store = new CheckpointStore(root, NoSync.INSTANCE, 3);
        byte[] first = checkpoint(10);
        store.publish(ID, first);
        Path second = store.publish(ID, checkpoint(20)).file();
        byte[] bytes = Files.readAllBytes(second);
        Files.write(second, Arrays.copyOf(bytes, bytes.length - 100));
        CheckpointStore.Loaded loaded = store.load(ID).orElseThrow();
        assertEquals(1, loaded.generation().number());
        assertArrayEquals(first, loaded.bytes());
        assertTrue(loaded.recovery().contains("gen-000000000002.ssnp, which is unreadable") && loaded.recovery().contains("truncated"), loaded.recovery());
    }

    @Test
    void aMissingCurrentFallsBackToo() throws IOException{
        var store = new CheckpointStore(root, NoSync.INSTANCE, 3);
        store.publish(ID, checkpoint(10));
        store.publish(ID, checkpoint(20));
        Files.delete(store.dir(ID).resolve("CURRENT"));
        CheckpointStore.Loaded loaded = store.load(ID).orElseThrow();
        assertEquals(2, loaded.generation().number());
        assertTrue(loaded.recovery().startsWith("there is no CURRENT"), loaded.recovery());
    }

    @Test
    void nothingReadableIsAnError() throws IOException{
        var store = new CheckpointStore(root, NoSync.INSTANCE, 3);
        Path only = store.publish(ID, checkpoint(10)).file();
        Files.write(only, new byte[]{1, 2, 3});
        IOException failure = assertThrows(IOException.class, () -> store.load(ID));
        assertTrue(failure.getMessage().contains("no readable checkpoint"), failure.getMessage());
    }

    @Test
    void matchCheckpointsAreKeptByTickWhateverRetentionDoes() throws IOException{
        var store = new CheckpointStore(root, NoSync.INSTANCE, 1);
        byte[] at6 = checkpoint(6);
        store.publishMatch(ID, checkpoint(0));
        store.publishMatch(ID, at6);
        store.publishMatch(ID, checkpoint(12));
        for(int i = 0; i < 4; i++) store.publish(ID, checkpoint(20 + i));
        assertEquals(List.of("gen-000000000004.ssnp"), store.generations(ID));
        assertEquals(List.of(0L, 6L, 12L), store.matchTicks(ID), "retention leaves them");
        assertArrayEquals(at6, store.loadMatch(ID, 6).orElseThrow());
        assertTrue(store.loadMatch(ID, 7).isEmpty());
        store.publishMatch(ID, at6);
        assertEquals(List.of(0L, 6L, 12L), store.matchTicks(ID), "the same tick again replaces it");

        assertEquals(1, store.dropMatchAfter(ID, 6), "a restore to tick 6 drops what came after it");
        assertEquals(List.of(0L, 6L), store.matchTicks(ID));
        Path archived = store.archive(ID, 99);
        assertEquals(List.of(), store.matchTicks(ID), "a fresh world starts a new match");
        assertArrayEquals(at6, Files.readAllBytes(archived.resolve("match/000000000006.ssnp")));
    }

    @Test
    void aFailedMatchCheckpointLeavesNothingBehind() throws IOException{
        var files = new Faulty();
        var store = new CheckpointStore(root, files, 3);
        byte[] bytes = checkpoint(6);
        for(String step : List.of("write", "move", "read")){
            files.failOn = step;
            assertThrows(IOException.class, () -> store.publishMatch(ID, bytes), step);
            assertEquals(List.of(), FileOps.SYSTEM.list(store.matchDir(ID)), "nothing left after a failed " + step);
        }
        files.failOn = null;
        files.shortWrites = true;
        assertThrows(IOException.class, () -> store.publishMatch(ID, bytes));
        assertEquals(List.of(), FileOps.SYSTEM.list(store.matchDir(ID)), "a short write is caught and removed");
        files.shortWrites = false;
        store.publishMatch(ID, bytes);
        assertEquals(List.of(6L), store.matchTicks(ID));
    }
}
