package com.xpdustry.shard.server.store;

import com.xpdustry.shard.server.Hang;
import com.xpdustry.shard.server.store.ChatLog.*;
import com.xpdustry.shard.server.store.StoreFixture.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

class BackupTest{
    @TempDir
    Path dir;

    static final ManualClock CLOCK = new ManualClock(1_800_000_000_000L);

    /** Each write adds a player and a session for it, so a consistent copy has as many sessions as players. */
    static CompletableFuture<?> pair(Store store, int i){
        return store.write(c -> {
            var player = Players.recordJoin(c, "uuid-" + i, null, "p" + i, "p" + i, "10.0.0." + (i % 250), CLOCK.millis(), true);
            return Sessions.start(c, player.id(), "alpha", "10.0.0." + (i % 250), "p" + i, "en", 146, false, false, CLOCK.millis());
        });
    }

    static long count(Connection connection, String table) throws SQLException{
        try(var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT count(*) FROM " + table)){
            rows.next();
            return rows.getLong(1);
        }
    }

    @Test
    void onlineBackupUnderWritesIsConsistentAndRestorable() throws Exception{
        try(var store = StoreFixture.open(dir, CLOCK)){
            for(int i = 0; i < 500; i++) pair(store, i);
            pair(store, 500).get(Hang.SECONDS, TimeUnit.SECONDS);

            // keep writing while the backup runs
            var writing = new AtomicBoolean(true);
            var written = new AtomicInteger(501);
            var writer = Thread.ofVirtual().start(() -> {
                while(writing.get()){
                    try{
                        pair(store, written.getAndIncrement()).get(Hang.SECONDS, TimeUnit.SECONDS);
                    }catch(Exception e){
                        throw new RuntimeException(e);
                    }
                }
            });
            var backup = store.backup().get(Hang.SECONDS, TimeUnit.SECONDS);
            writing.set(false);
            writer.join();

            assertTrue(backup.path().startsWith(dir.resolve("backups").toAbsolutePath()), backup.path().toString());
            assertTrue(backup.path().getFileName().toString().matches("shard-\\d{8}-\\d{6}Z\\.db"), backup.path().toString());
            assertEquals(Files.size(backup.path()), backup.bytes());
            assertEquals(Migrations.latest(), backup.schemaVersion());
            assertEquals(Migrations.latest(), Store.verify(backup.path()));
            try(var files = Files.list(backup.path().getParent())){
                assertEquals(List.of(backup.path()), files.toList(), "no partial file left behind");
            }

            long players, sessions;
            try(var connection = DriverManager.getConnection("jdbc:sqlite:" + backup.path())){
                players = count(connection, "players");
                sessions = count(connection, "sessions");
            }
            assertEquals(players, sessions, "the backup is one snapshot");
            assertTrue(players >= 501 && players <= written.get(), players + " of " + written.get());

            // a backup opens as a store of its own, with nothing to migrate
            Path restored = Files.createDirectories(dir.resolve("restored"));
            Files.copy(backup.path(), restored.resolve("shard.db"));
            try(var copy = StoreFixture.open(restored, CLOCK)){
                assertEquals(players, copy.read(c -> count(c, "players")).get(Hang.SECONDS, TimeUnit.SECONDS));
                assertEquals("p7", copy.read(c -> Players.byUuid(c, "uuid-7")).get(Hang.SECONDS, TimeUnit.SECONDS).orElseThrow().lastName());
            }
            assertFalse(Files.exists(restored.resolve("backups")));
        }
    }

    @Test
    void aBackupNeverOverwrites() throws Exception{
        try(var store = StoreFixture.open(dir, CLOCK)){
            Path target = dir.resolve("copy.db");
            store.backup(target).get(Hang.SECONDS, TimeUnit.SECONDS);
            var again = assertThrows(ExecutionException.class, () -> store.backup(target).get(Hang.SECONDS, TimeUnit.SECONDS));
            assertTrue(again.getCause().getMessage().contains("exists"), again.getCause().getMessage());
        }
    }

    @Test
    void verifyRejectsACorruptFile() throws Exception{
        try(var store = StoreFixture.open(dir, CLOCK)){
            for(int i = 0; i < 200; i++) pair(store, i);
            store.write(c -> ChatLog.append(c, List.of(new Message(0, 1, "a", null, null, Channel.GLOBAL, "x".repeat(2000), true)))).get(Hang.SECONDS, TimeUnit.SECONDS);
            Path target = dir.resolve("copy.db");
            store.backup(target).get(Hang.SECONDS, TimeUnit.SECONDS);
            byte[] bytes = Files.readAllBytes(target);
            for(int i = 4096; i < bytes.length; i += 97) bytes[i] ^= 0x5a; // damage every page past the header
            Files.write(target, bytes);
            assertThrows(Exception.class, () -> Store.verify(target));
        }
    }
}
