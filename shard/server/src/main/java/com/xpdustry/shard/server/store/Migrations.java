package com.xpdustry.shard.server.store;

import java.io.*;
import java.nio.charset.*;
import java.security.*;
import java.sql.*;
import java.util.*;

/**
 * The checked SQL migrations (note 34 "Migration and backup"). Each is a numbered file in
 * {@code resources/.../store/migrations/}, listed in {@link #NAMES}; {@code schema_migrations} records each applied
 * version with the SHA-256 of its text. Opening a database:
 *
 * <ol>
 *   <li>refuses a schema newer than this build, a gap, or an applied migration whose text changed;</li>
 *   <li>takes an online backup first when an existing database has migrations pending;</li>
 *   <li>applies each pending migration in its own transaction, then runs {@code PRAGMA foreign_key_check}.</li>
 * </ol>
 *
 * <p>There is no downgrade. A destructive change copies into new tables and keeps the backup.
 */
public final class Migrations{
    /** Every migration, in order. Version {@code n} is {@code NAMES.get(n - 1)}. */
    static final List<String> NAMES = List.of("0001_init", "0002_kicks_name_bans");

    static final String BOOTSTRAP = """
        CREATE TABLE IF NOT EXISTS schema_migrations(
            version INTEGER PRIMARY KEY,
            name TEXT NOT NULL,
            checksum TEXT NOT NULL,
            applied_at INTEGER NOT NULL
        ) STRICT""";

    /** One migration file. */
    public record Migration(int version, String name, String sql, String checksum){
        /** The statements, split at lines that end with {@code ;}, without comment lines. No triggers. */
        public List<String> statements(){
            var statements = new ArrayList<String>();
            var current = new StringBuilder();
            for(String line : sql.split("\n")){
                String trimmed = line.strip();
                if(trimmed.isEmpty() || trimmed.startsWith("--")) continue;
                current.append(line).append('\n');
                if(trimmed.endsWith(";")){
                    statements.add(current.toString().strip());
                    current.setLength(0);
                }
            }
            if(!current.toString().isBlank()) throw new IllegalStateException("migration " + name + " ends without ';'");
            return statements;
        }
    }

    /** The migrations this build knows. */
    public static List<Migration> all(){
        var migrations = new ArrayList<Migration>();
        for(int i = 0; i < NAMES.size(); i++){
            String name = NAMES.get(i);
            if(!name.startsWith(String.format("%04d_", i + 1))) throw new IllegalStateException("migration " + name + " is out of order");
            String sql = resource("migrations/" + name + ".sql");
            migrations.add(new Migration(i + 1, name, sql, checksum(sql)));
        }
        return migrations;
    }

    public static int latest(){
        return NAMES.size();
    }

    /** The applied versions and their checksums, by version. Creates {@code schema_migrations} if needed. */
    static SortedMap<Integer, String> applied(Connection connection) throws SQLException{
        try(var statement = connection.createStatement()){
            statement.execute(BOOTSTRAP);
        }
        var applied = new TreeMap<Integer, String>();
        try(var statement = connection.createStatement();
            var rows = statement.executeQuery("SELECT version, checksum FROM schema_migrations ORDER BY version")){
            while(rows.next()) applied.put(rows.getInt(1), rows.getString(2));
        }
        return applied;
    }

    /**
     * Checks the applied history and returns the pending migrations.
     *
     * @throws StoreException for a newer schema, a gap or a changed migration
     */
    static List<Migration> pending(SortedMap<Integer, String> applied){
        var known = all();
        int expected = 1;
        for(var entry : applied.entrySet()){
            int version = entry.getKey();
            if(version > known.size()){
                throw new StoreException("the database has schema version " + applied.lastKey() + ", newer than this build's "
                    + known.size() + "; run a newer Shard or restore a backup");
            }
            if(version != expected) throw new StoreException("schema_migrations skips version " + expected);
            var migration = known.get(version - 1);
            if(!migration.checksum().equals(entry.getValue())){
                throw new StoreException("migration " + migration.name() + " changed after it was applied (checksum "
                    + entry.getValue() + ", now " + migration.checksum() + ")");
            }
            expected++;
        }
        return known.subList(applied.size(), known.size());
    }

    /** Applies one migration on {@code connection}, which is in auto-commit mode, in a transaction. */
    static void apply(Connection connection, Migration migration, long now) throws SQLException{
        connection.setAutoCommit(false);
        try{
            try(var statement = connection.createStatement()){
                for(String sql : migration.statements()) statement.execute(sql);
            }
            try(var insert = connection.prepareStatement("INSERT INTO schema_migrations(version, name, checksum, applied_at) VALUES (?, ?, ?, ?)")){
                insert.setInt(1, migration.version());
                insert.setString(2, migration.name());
                insert.setString(3, migration.checksum());
                insert.setLong(4, now);
                insert.executeUpdate();
            }
            try(var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA foreign_key_check")){
                if(rows.next()) throw new SQLException("migration " + migration.name() + " broke a foreign key in table " + rows.getString(1));
            }
            connection.commit();
        }catch(SQLException | RuntimeException e){
            connection.rollback();
            throw e;
        }finally{
            connection.setAutoCommit(true);
        }
    }

    static String checksum(String sql){
        try{
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(sql.replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        }catch(NoSuchAlgorithmException e){
            throw new AssertionError(e);
        }
    }

    private static String resource(String name){
        try(var in = Migrations.class.getResourceAsStream(name)){
            if(in == null) throw new IllegalStateException("missing migration resource " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }catch(IOException e){
            throw new UncheckedIOException(e);
        }
    }

    private Migrations(){}
}
