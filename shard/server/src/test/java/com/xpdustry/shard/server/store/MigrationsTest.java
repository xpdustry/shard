package com.xpdustry.shard.server.store;

import com.xpdustry.shard.server.store.StoreFixture.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.net.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.stream.*;

import static org.junit.jupiter.api.Assertions.*;

class MigrationsTest{
    @TempDir
    Path dir;

    static final ManualClock CLOCK = new ManualClock(1_800_000_000_000L);
    static final List<String> TABLES = List.of("admins", "bans", "chat_log", "kicks", "name_bans", "notes", "player_addresses", "player_names",
        "players", "schema_migrations", "sessions", "whitelist");

    Connection raw() throws SQLException{
        return DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("shard.db"));
    }

    @Test
    void upFromEmpty() throws Exception{
        try(var store = StoreFixture.open(dir, CLOCK)){
            assertEquals(Migrations.latest(), store.schemaVersion());
        }
        try(var connection = raw(); var statement = connection.createStatement()){
            var tables = new ArrayList<String>();
            try(var rows = statement.executeQuery("SELECT name FROM sqlite_schema WHERE type = 'table' ORDER BY name")){
                while(rows.next()) tables.add(rows.getString(1));
            }
            assertEquals(TABLES, tables);
            try(var rows = statement.executeQuery("SELECT version, name, checksum, applied_at FROM schema_migrations ORDER BY version")){
                for(var migration : Migrations.all()){
                    assertTrue(rows.next());
                    assertEquals(migration.version(), rows.getInt(1));
                    assertEquals(migration.name(), rows.getString(2));
                    assertEquals(migration.checksum(), rows.getString(3));
                    assertEquals(CLOCK.millis(), rows.getLong(4));
                }
                assertFalse(rows.next());
            }
            try(var rows = statement.executeQuery("PRAGMA foreign_key_check")){
                assertFalse(rows.next());
            }
            try(var rows = statement.executeQuery("PRAGMA integrity_check")){
                rows.next();
                assertEquals("ok", rows.getString(1));
            }
        }
    }

    @Test
    void reopeningAppliesNothing() throws Exception{
        StoreFixture.open(dir, CLOCK).close();
        var later = new ManualClock(CLOCK.millis() + 60_000);
        StoreFixture.open(dir, later).close();
        try(var connection = raw(); var statement = connection.createStatement();
            var rows = statement.executeQuery("SELECT count(*), max(applied_at) FROM schema_migrations")){
            rows.next();
            assertEquals(Migrations.latest(), rows.getInt(1));
            assertEquals(CLOCK.millis(), rows.getLong(2));
        }
        assertFalse(Files.exists(dir.resolve("backups")), "no pending migration, so no pre-migration backup");
    }

    @Test
    void anEditedMigrationIsRefused() throws Exception{
        StoreFixture.open(dir, CLOCK).close();
        try(var connection = raw(); var statement = connection.createStatement()){
            statement.executeUpdate("UPDATE schema_migrations SET checksum = 'abc' WHERE version = 1");
        }
        var error = assertThrows(StoreException.class, () -> StoreFixture.open(dir, CLOCK));
        assertTrue(error.getMessage().contains("migration 0001_init changed after it was applied"), error.getMessage());
    }

    @Test
    void aNewerSchemaIsRefused() throws Exception{
        StoreFixture.open(dir, CLOCK).close();
        int newer = Migrations.latest() + 1;
        try(var connection = raw(); var statement = connection.createStatement()){
            statement.executeUpdate("INSERT INTO schema_migrations VALUES (" + newer + ", 'future', 'x', 0)");
        }
        var error = assertThrows(StoreException.class, () -> StoreFixture.open(dir, CLOCK));
        assertTrue(error.getMessage().contains("schema version " + newer + ", newer than this build's " + Migrations.latest()), error.getMessage());
    }

    @Test
    void aGapIsRefused(){
        var applied = new TreeMap<Integer, String>();
        applied.put(2, "x");
        assertThrows(StoreException.class, () -> Migrations.pending(applied));
    }

    @Test
    void everyMigrationFileIsListedAndSplits() throws Exception{
        URL url = Migrations.class.getResource("migrations");
        assertNotNull(url);
        try(var files = Files.list(Path.of(url.toURI()))){
            assertEquals(Migrations.NAMES, files.map(f -> f.getFileName().toString().replace(".sql", "")).sorted().collect(Collectors.toList()));
        }
        for(var migration : Migrations.all()){
            var statements = migration.statements();
            assertEquals(migration.sql().split("\\bCREATE ").length - 1, statements.size(), migration.name());
            for(String statement : statements) assertTrue(statement.startsWith("CREATE ") && statement.endsWith(";"), statement);
        }
    }

    @Test
    void foreignKeysAndChecksHold() throws Exception{
        try(var store = StoreFixture.open(dir, CLOCK)){
            var orphan = store.write(c -> Notes.add(c, 999, "console", "no such player", CLOCK.millis()));
            assertThrows(Exception.class, orphan::join, "notes.player_id references players");
            var badKind = store.write(c -> {
                try(var statement = c.createStatement()){
                    return statement.executeUpdate("INSERT INTO bans(kind, target, created_at, created_by) VALUES ('name', 'x', 1, 'console')");
                }
            });
            assertThrows(Exception.class, badKind::join, "bans.kind is checked");
            var badExpiry = store.write(c -> Bans.insert(c, Bans.Kind.UUID, "u", null, "", 10, "console", 5L));
            assertThrows(Exception.class, badExpiry::join, "a ban expires after it starts");
        }
    }
}
